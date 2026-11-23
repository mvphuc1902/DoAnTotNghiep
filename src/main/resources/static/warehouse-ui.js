async function warehouseRequest(path,body){const response=await fetch(erpApi+'/warehouse/'+path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body||{})});const data=await response.json().catch(()=>({}));if(!response.ok)throw Error(data.message||data.detail||'Không thực hiện được thao tác kho.');return data;}
function warehouseForm(title,fields,submit,setup){
 const dialog=erpElement('dialog',null,'profile-card purchase-action-dialog');dialog.style.cssText='padding:0;width:min(900px,95vw);max-height:90vh;overflow:auto';
 const form=erpElement('form'),controls={},bar=erpElement('div',null,'profile-close'),section=erpElement('section',null,'profile-section'),grid=erpElement('div',null,'profile-fields');form.append(bar,profileHeader('Warehouse · Biểu mẫu',title,'Thao tác kho'));section.append(grid);form.append(section);
 for(const field of fields){const label=erpElement('label',field.label,'profile-field');label.style.cssText='display:block';const input=erpElement(field.options?'select':'input');input.style.cssText='display:block;width:100%;padding:8px;border:1px solid #cbd5e1';input.required=field.required!==false;
  if(field.options){const empty=erpElement('option','Chọn…');empty.value='';input.append(empty);field.options.forEach(([value,text])=>{const option=erpElement('option',text);option.value=value;input.append(option);});}else{input.type=field.type||'text';if(input.type==='number'){input.min='0';input.step='0.001';}}
  if(field.type==='hidden')label.hidden=true;if(field.readonly)input.readOnly=true;if(field.value!=null)input.value=field.value;controls[field.key]=input;label.append(input);grid.append(label);
 }
 const error=erpElement('p','','text-rose-600');error.setAttribute('role','alert');const save=erpButton('Xác nhận');save.type='submit';section.append(error);bar.append(erpButton('Đóng',()=>dialog.close()),save);const requestKey=crypto.randomUUID();
 form.onsubmit=async event=>{event.preventDefault();save.disabled=true;try{await submit(Object.fromEntries(Object.entries(controls).map(([key,input])=>[key,input.value])),requestKey);dialog.close();}catch(e){error.textContent=e.message;}finally{save.disabled=false;}};
 if(setup)setup(controls,save,error);dialog.append(form);document.body.append(dialog);dialog.onclose=()=>dialog.remove();dialog.showModal();
}
async function warehouseAll(table){const rows=[];for(let page=0;;page++){const data=await erpGet('/tables/'+table+'?'+new URLSearchParams({size:100,page}));rows.push(...data.rows);if(data.rows.length<100||rows.length>=Number(data.total))return rows;}}
async function mountWarehouseActions(host,row){
 const section=erpElement('section',null,'profile-section');host.append(section);
 try{
  const state=await erpGet('/warehouse/locations/'+row.id);if(!section.isConnected)return;
  section.append(erpElement('h3','Warehouse Operations · Zone / Bin'));
  const error=erpElement('p','','text-rose-600');section.append(error);
  const refresh=async()=>{section.remove();host.querySelector('[data-warehouse-summary]')?.remove();await showWarehouseVendorSummary(host,'locations',row);await mountWarehouseActions(host,row);};
  const action=(label,fn)=>section.append(erpButton(label,async event=>{const button=event.currentTarget;button.disabled=true;try{await fn();}catch(e){error.textContent=e.message;}finally{button.disabled=false;}}));
  if(!state.enabled){
   section.append(erpElement('p','Kho đang quản lý theo tổng tồn. Bật Bin sẽ phân bổ tồn Item hiện tại vào STORAGE, không cộng thêm tồn. Từ đó: nhận vào RECEIVE → Put-away → STORAGE → Pick → SHIP → Post Shipment.','document-hint'));
   action('Bật quản lý Zone / Bin',async()=>{if(!await confirmAction('Bật quản lý Bin cho kho '+row.code+'? Tồn Item hiện tại được ghi số dư đầu vào STORAGE. Sau đó giao Sales phải Pick trước.',{danger:false}))return;await warehouseRequest('locations/'+row.id+'/enable');await refresh();});return;
  }
  section.append(erpElement('p','Nhận hàng qua Post Receive trên PO → RECEIVE. Put-away đưa vào STORAGE/QC. Pick đưa hàng sang SHIP trước khi Post Shipment. Movement không đổi tổng tồn; Transfer dưới đây là chuyển trực tiếp giữa hai kho.','document-hint'));
  action('Thêm Zone',()=>warehouseForm('Tạo Zone',[{key:'code',label:'Mã Zone'},{key:'name',label:'Tên Zone'}],async values=>{await warehouseRequest('locations/'+row.id+'/zones',values);await refresh();}));
  action('Thêm Bin',()=>warehouseForm('Tạo Bin',[{key:'zoneId',label:'Zone',options:state.zones.map(z=>[z.id,z.code+' — '+z.name])},{key:'code',label:'Mã Bin'},{key:'type',label:'Loại Bin',options:['RECEIVING','STORAGE','PICKING','SHIPPING','QC','QUARANTINE','RETURN'].map(t=>[t,t])},{key:'capacity',label:'Sức chứa (số đơn vị; bỏ trống nếu không giới hạn)',type:'number',required:false}],async values=>{await warehouseRequest('locations/'+row.id+'/bins',{...values,capacity:values.capacity||null});await refresh();}));
  action('Cấu hình Bin',()=>warehouseForm('Sức chứa / khóa Bin',[{key:'id',label:'Bin',options:state.bins.map(b=>[b.id,b.code])},{key:'capacity',label:'Sức chứa mới (bỏ trống = không giới hạn)',type:'number',required:false},{key:'blocked',label:'Trạng thái',options:[['false','Hoạt động'],['true','Khóa']]}],async values=>{await warehouseRequest('bins/'+values.id+'/config',{capacity:values.capacity||null,blocked:values.blocked==='true'});await refresh();}));
  for(const [type,label] of [['PUTAWAY','Put-away'],['PICK','Pick'],['MOVE','Movement / QC'],['TRANSFER','Transfer trực tiếp'],['COUNT','Lập phiếu kiểm kê']])action(label,async()=>{
   const [items,bins,locations]=await Promise.all([warehouseAll('items'),type==='TRANSFER'?warehouseAll('warehouse_bins'):Promise.resolve(state.bins),type==='TRANSFER'?warehouseAll('locations'):Promise.resolve([])]);
   const names=new Map(locations.map(l=>[String(l.id),l.code]));
   const dest=type==='TRANSFER'?bins.filter(b=>String(b.location_id)!==String(row.id)):bins;
   const fields=[{key:'itemId',label:'Item',options:items.filter(i=>i.item_category==='INVENTORY'&&!i.blocked).map(i=>[i.id,i.item_no+' — '+i.description])},{key:'fromBinId',label:'Bin nguồn / Bin kiểm kê',options:state.bins.filter(b=>!b.blocked).map(b=>[b.id,b.code+' · '+b.bin_type])}];
   if(type!=='COUNT')fields.push({key:'toBinId',label:'Bin đích',options:dest.filter(b=>!b.blocked).map(b=>[b.id,(names.get(String(b.location_id))||row.code)+' / '+b.code+' · '+b.bin_type])});
   if(type==='COUNT')fields.push({key:'expectedQuantity',label:'Số lượng hệ thống đã chốt',readonly:true},{key:'snapshotEntryId',label:'',type:'hidden'});
   fields.push({key:'quantity',label:type==='COUNT'?'Số lượng kiểm đếm thực tế':'Số lượng',type:'number'},{key:'reason',label:'Lý do / ghi chú'});
   warehouseForm(label,fields,async(values,requestKey)=>{const result=await warehouseRequest('register',{...values,toBinId:values.toBinId||null,type,requestKey});notifyCustomer('Đã tạo '+result.document_no);await refresh();},type==='COUNT'?(controls,save,error)=>{
    let version=0;const snapshot=async()=>{const request=++version;save.disabled=true;controls.expectedQuantity.value='';controls.snapshotEntryId.value='';controls.quantity.value='';if(!controls.itemId.value||!controls.fromBinId.value)return;try{const data=await erpGet('/warehouse/snapshot?'+new URLSearchParams({binId:controls.fromBinId.value,itemId:controls.itemId.value}));if(request!==version)return;controls.expectedQuantity.value=data.quantity;controls.snapshotEntryId.value=data.revision;save.disabled=false;error.textContent='';}catch(e){error.textContent=e.message;}};controls.itemId.onchange=snapshot;controls.fromBinId.onchange=snapshot;save.disabled=true;
   }:null);
  });
  const wrap=erpElement('div',null,'overflow-auto'),table=erpElement('table',null,'min-w-full text-sm');const head=erpElement('tr');['Bin','Loại','Item','Tên','ĐVT','Số lượng'].forEach(label=>head.append(erpElement('th',label,'px-3 py-2')));const thead=erpElement('thead');thead.append(head);table.append(thead);const body=erpElement('tbody');state.stock.forEach(s=>{const tr=erpElement('tr');[s.bin_code,s.bin_type,s.item_no,s.description,s.base_unit_of_measure,s.quantity].forEach(v=>tr.append(erpElement('td',String(v??'—'),'px-3 py-2')));body.append(tr);});table.append(body);wrap.append(table);section.append(wrap);
  for(const count of state.counts){const line=erpElement('div',null,'flex gap-3 items-center');line.append(erpElement('span',count.document_no+' · '+count.bin_code+' · '+count.item_no+' · hệ thống '+count.expected_quantity+' / thực tế '+count.counted_quantity+' · '+count.status));const stage=count.status==='PENDING'?'approve':'post';line.append(erpButton(stage==='approve'?'Phê duyệt':'Post điều chỉnh',async event=>{const button=event.currentTarget;button.disabled=true;try{await warehouseRequest('counts/'+count.id+'/'+stage);await refresh();}catch(e){error.textContent=e.message;}finally{button.disabled=false;}}));section.append(line);}
  action('Warehouse Entries',()=>openErpTable('warehouse_entries','location_id',row.id));
 }catch(e){section.append(erpElement('p',e.message,'text-rose-600'));}
}
