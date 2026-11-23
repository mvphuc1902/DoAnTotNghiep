function reportDateColumn(table,columns,documentType) {
  if(table==='purchase_headers'&&documentType==='INVOICE'&&columns.includes('posting_date'))return 'posting_date';
  const preferred={sales_headers:'order_date',sales_orders:'order_date',sales_invoices:'posting_date',sales_returns:'return_date',purchase_headers:'order_date',purchase_receipts:'receipt_date',purchase_returns:'return_date',warehouse_entries:'created_at',warehouse_operations:'created_at',purchase_vendor_entries:'posting_date',inventory_ledger:'posting_date',general_ledger:'posting_date',payments:'payment_date'}[table];
  return columns.includes(preferred)?preferred:['posting_date','document_date','order_date','created_at'].find(c=>columns.includes(c));
}
function relatedReportContexts(context) {
 const row=context.row;
 const entry=(table,column,value)=>({table,row,filterColumn:column,filterValue:value,related:true});
 if(context.table==='locations')return [entry('warehouse_bin_inventory','location_id',row.id),entry('warehouse_entries','location_id',row.id),entry('inventory_ledger','location_id',row.id),entry('purchase_receipts','location_code',row.code)];
 if(context.table==='vendors')return [entry('purchase_vendor_entries','vendor_id',row.id),entry('purchase_headers','vendor_id',row.id),entry('purchase_receipts','vendor_id',row.id)];
 if(context.table==='warehouse_operations')return [entry('warehouse_entries','operation_id',row.id),entry('general_ledger','document_no',row.document_no)];
 if(context.table==='warehouse_bins')return [entry('warehouse_bin_inventory','bin_id',row.id),entry('warehouse_entries','bin_id',row.id)];
 return [];
}
function openReportOptions(context) {
  const dialog=erpElement('dialog',null,'rounded-xl p-6 max-w-xl w-full'),form=erpElement('form',null,'space-y-4');
  form.append(erpElement('h2','Report · '+(tableLabels[context.table]||context.table),'text-xl font-bold'));
  const originalContext=context,choices=[context,...relatedReportContexts(context)],dataset=erpElement('select');dataset.setAttribute('aria-label','Nội dung báo cáo');choices.forEach((choice,index)=>{const option=erpElement('option',tableLabels[choice.table]||choice.table);option.value=index;dataset.append(option);});if(choices.length>1){const label=erpElement('label','Nội dung báo cáo');label.append(dataset);form.append(label);}
  const scope=erpElement('select');scope.setAttribute('aria-label','Phạm vi báo cáo');
  for(const [value,text] of [['range','Danh sách cùng loại trong khoảng ngày'],['current','Chứng từ / bản ghi đang mở']]){const o=erpElement('option',text);o.value=value;scope.append(o);}
  const from=erpElement('input'),to=erpElement('input');from.type=to.type='date';
  const dateRow=erpElement('div',null,'flex gap-3');
  [['From Date',from],['To Date',to]].forEach(([text,input])=>{const label=erpElement('label',text);input.setAttribute('aria-label',text);label.append(input);dateRow.append(label);});
  const error=erpElement('p','','text-rose-600');error.setAttribute('role','alert');
  const dateHint=erpElement('p',null,'document-hint');dateRow.after(dateHint);
  const snapshotTables=new Set(['warehouse_bin_inventory','inventory','locations','vendors','warehouse_bins','warehouse_zones']);
  scope.onchange=()=>{const snapshot=snapshotTables.has(context.table);dateRow.hidden=scope.value==='current'||snapshot;from.disabled=to.disabled=snapshot;if(snapshot){from.value='';to.value='';}dateHint.textContent=snapshot?'Số dư hiện tại / danh mục: không áp dụng khoảng ngày. Chọn lịch sử hoặc công nợ để lọc From Date / To Date.':'Lọc theo '+(context.table==='warehouse_entries'||context.table==='warehouse_operations'?'ngày phát sinh':context.table==='purchase_receipts'?'ngày nhận hàng':context.table==='purchase_headers'?'ngày chứng từ':'Posting Date / ngày chứng từ')+'. Bao gồm cả ngày bắt đầu và kết thúc.';};dataset.onchange=()=>{context=choices[Number(dataset.value)]||originalContext;scope.disabled=!!context.related;if(context.related)scope.value='range';scope.onchange();};
  const print=erpButton('Xem trước');print.type='submit';
  form.append(scope,dateRow,dateHint,erpElement('p','Bỏ trống ngày để lấy toàn bộ. Từ ngày và đến ngày đều được tính trong báo cáo.','document-hint'),error,erpButton('Đóng',()=>dialog.close()),print);
  form.onsubmit=async e=>{
    e.preventDefault();error.textContent='';
    if(scope.value==='range'&&from.value&&to.value&&from.value>to.value){error.textContent='From Date phải nhỏ hơn hoặc bằng To Date.';return;}
    if(scope.value==='current'&&documentReportAdapters.has(context.table)){await documentReportAdapters.get(context.table)(context.table,context.row,print);return;}
    await exportRangeReport(context,scope.value==='current',from.value,to.value,print,error);
  };
  scope.onchange();dialog.append(form);document.body.append(dialog);dialog.addEventListener('close',()=>dialog.remove());dialog.showModal();
}
async function exportRangeReport(context,current,from,to,button,error) {
  const popup=window.open('','_blank');if(!popup){error.textContent='Cho phép cửa sổ bật lên để mở Report.';return;}popup.opener=null;popup.document.body.textContent='Đang tải báo cáo…';button.disabled=true;
  const esc=v=>String(v??'—').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  try{
    let rows=[],columns=[];
    for(let page=0;;page++){
      const params=new URLSearchParams({page,size:100});
      if(context.filterColumn){params.set('filterColumn',context.filterColumn);params.set('filterValue',context.filterValue);}
      else if(current){params.set('filterColumn',context.table==='item_bom'?'parent_item_id':'id');params.set('filterValue',context.table==='item_bom'?context.row.parent_item_id:context.row.id);}
      else if(context.row.document_type&&['sales_headers','purchase_headers'].includes(context.table)){params.set('filterColumn','document_type');params.set('filterValue',context.row.document_type);}
      const result=await erpGet('/tables/'+context.table+'?'+params);columns=result.columns;rows.push(...result.rows);
      if(result.rows.length<100||rows.length>=Number(result.total))break;
    }
    const dateColumn=reportDateColumn(context.table,columns,context.row.document_type);
    if(!current&&(from||to)&&!dateColumn)throw Error('Danh mục này không có trường ngày để lọc. Bỏ trống From Date / To Date để xuất toàn bộ.');
    if(!current&&(from||to))rows=rows.filter(row=>{const date=String(row[dateColumn]||'').slice(0,10);return /^\d{4}-\d{2}-\d{2}$/.test(date)&&(!from||date>=from)&&(!to||date<=to);});
    columns=columns.filter(c=>!['password','password_hash'].includes(c));
    if(context.table.startsWith('warehouse_')||context.table==='purchase_vendor_entries')columns=columns.filter(c=>c!=='id'&&!c.endsWith('_id')); 
    const title=tableLabels[context.table]||context.table;
    const html='<!doctype html><html lang="vi"><head><meta charset="utf-8"><title>'+esc(title)+'</title><style>'+documentReportStyles+' .sheet{width:fit-content;min-width:90%;max-width:none} @page{size:A4 landscape}</style></head><body><div class="toolbar"><button onclick="window.print()">In / Lưu PDF</button><button onclick="window.close()">Đóng</button></div><main class="sheet"><header class="top"><div class="brand"><div class="logo">E</div><div><p class="eyebrow">ERP Enterprise</p><h1>'+esc(title)+'</h1></div></div><div class="doc-meta">Ngày xuất: '+esc(new Date().toLocaleDateString('vi-VN'))+'</div></header><section class="title"><h2>'+esc(context.related?(context.row.code||context.row.vendor_no||context.row.document_no||context.row.name):title)+'</h2></section><p>'+esc(current?'Bản ghi đang mở':(from||'Không giới hạn')+' → '+(to||'Không giới hạn'))+(dateColumn?' · '+esc(columnLabels[dateColumn]||dateColumn):'')+'</p><p>'+rows.length+' bản ghi</p><table><thead><tr>'+columns.map(c=>'<th>'+esc(columnLabels[c]||c)+'</th>').join('')+'</tr></thead><tbody>'+rows.map(row=>'<tr>'+columns.map(c=>'<td>'+esc(erpValue(row[c],c))+'</td>').join('')+'</tr>').join('')+'</tbody></table><footer class="footer"><span>'+esc(title)+'</span><span>Được tạo từ ERP Enterprise</span></footer></main></body></html>';
    popup.document.open();popup.document.write(html);popup.document.close();
    mountReportDownloads(popup,{name:title,landscape:true,columns,rows,labels:columnLabels,period:current?'Bản ghi đang mở':(from||'Không giới hạn')+' → '+(to||'Không giới hạn')});
  }catch(e){error.textContent=e.message;popup.document.body.textContent='Không xuất được Report: '+e.message;}finally{button.disabled=false;}
}
