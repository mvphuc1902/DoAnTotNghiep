async function purchaseWorkflowRequest(path, body, method = 'POST') {
  const response = await fetch(erpApi + '/purchase-workflow/' + path, {
    method, headers: {'Content-Type':'application/json',Accept:'application/json'},
    body: JSON.stringify(body || {}), signal: AbortSignal.timeout(30000)
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.message || data.detail || `Không thực hiện được (HTTP ${response.status}).`);
  return data;
}
async function purchaseReload(id) {
  const data = await erpGet('/tables/purchase_headers?filterColumn=id&filterValue=' + encodeURIComponent(id));
  if (data.rows.length) showErpDetail('purchase_headers',data.rows[0],data.columns);
}
function purchaseWorkflowForm(title, fields, submit) {
  const dialog=erpElement('dialog'); dialog.className='profile-card purchase-action-dialog';
  dialog.style.cssText='max-width:900px;width:90vw;max-height:85vh;overflow:auto;padding:24px';
  const form=erpElement('form'); const controls={};
  form.append(erpElement('h2',title));
  for(const [key,label,value,type] of fields) {
    const wrapper=erpElement('label',label); wrapper.style.cssText='display:block;margin:12px 0';
    const input=erpElement('input'); input.type=type || 'text'; input.value=value ?? '';
    input.style.cssText='display:block;width:100%;padding:8px;border:1px solid #cbd5e1';
    if(type==='number') {input.min='0';input.step=key.startsWith('line_')?'0.001':'0.01';}
    wrapper.append(input);form.append(wrapper);controls[key]=input;
  }
  const error=erpElement('p','','text-rose-600');error.setAttribute('role','alert');
  const save=erpButton('Xác nhận',()=>{});save.type='submit';
  form.append(error,save,erpButton('Đóng',()=>dialog.close()));
  const requestKey=crypto.randomUUID();
  form.addEventListener('submit',async event=>{
    event.preventDefault();save.disabled=true;error.textContent='';
    try {await submit(Object.fromEntries(Object.entries(controls).map(([k,v])=>[k,v.value])),requestKey);dialog.close();}
    catch(e){error.textContent=e.message;}finally{save.disabled=false;}
  });
  dialog.append(form);document.body.append(dialog);dialog.addEventListener('close',()=>dialog.remove(),{once:true});dialog.showModal();
}
function purchaseToday() { const d=new Date(); return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`; }
async function purchaseSetup() {
  const data=await erpGet('/purchase-workflow/setup');
  const dialog=erpElement('dialog');dialog.className='profile-card purchase-action-dialog';dialog.style.cssText='padding:24px;max-width:800px;width:90vw;max-height:85vh;overflow:auto';
  const form=erpElement('form');form.append(erpElement('h2','Cấu hình hạch toán Purchase'));
  form.append(erpElement('p','Chọn tài khoản trong Accounts. G/L dùng VND; chưa hỗ trợ quy đổi ngoại tệ.'));
  const labels={INVENTORY:'Hàng tồn kho',EXPENSE:'Chi phí mua',VAT:'Thuế đầu vào',PAYABLE:'Phải trả nhà cung cấp',BANK:'Tiền thanh toán',RETURN_CLEARING:'Hàng đã gửi trả chờ giảm công nợ',ADJUSTMENT:'Điều chỉnh giá mua'};
  const inputs={};
  for(const role of data.roles) {
    const label=erpElement('label',labels[role]);label.style.cssText='display:block;margin:12px 0';
    const select=erpElement('select');select.required=true;select.style.cssText='display:block;width:100%;padding:8px';
    const blank=erpElement('option','Chọn tài khoản');blank.value='';select.append(blank);
    for(const account of data.accounts){const o=erpElement('option',account.account_no+' · '+account.account_name);o.value=account.id;select.append(o);}
    select.value=data.mappings.find(m=>m.role===role)?.account_id || '';inputs[role]=select;label.append(select);form.append(label);
  }
  const error=erpElement('p','','text-rose-600');const save=erpButton('Lưu cấu hình',()=>{});save.type='submit';
  form.append(error,save,erpButton('Đóng',()=>dialog.close()));
  form.addEventListener('submit',async e=>{e.preventDefault();save.disabled=true;try{await purchaseWorkflowRequest('setup',Object.fromEntries(Object.entries(inputs).map(([k,v])=>[k,Number(v.value)])),'PUT');dialog.close();notifyCustomer('Đã lưu cấu hình Purchase.');}catch(e){error.textContent=e.message;}finally{save.disabled=false;}});
  dialog.append(form);document.body.append(dialog);dialog.addEventListener('close',()=>dialog.remove(),{once:true});dialog.showModal();
}
async function showPurchaseWorkflow(detail,row) {
  const area=erpElement('section',null,'profile-section');const bar=erpElement('div',null,'profile-actions');
  const error=erpElement('p','','text-rose-600');error.setAttribute('role','alert');area.append(bar,error);detail.append(area);
  function action(label,fn) {
    bar.append(erpButton(label,async event=>{const button=event.currentTarget;button.disabled=true;error.textContent='';try{await fn();}catch(e){error.textContent=e.message;}finally{button.disabled=false;}}));
  }
  action('Cấu hình hạch toán',purchaseSetup);
  if(row.status==='DRAFT') action('Sửa chứng từ và Lines',()=>{document.getElementById('erp-detail-dialog').close();return openErpForm('purchase_headers',row);});
  if(row.document_type==='QUOTE' && row.status==='DRAFT') action('Make Order',async()=>{
    const result=await purchaseWorkflowRequest(row.id+'/make-order');await purchaseReload(result.id);
  });
  if(['ORDER','RETURN_ORDER'].includes(row.document_type) && row.status==='DRAFT') action('Release',async()=>{
    await purchaseWorkflowRequest(row.id+'/release');await purchaseReload(row.id);
  });
  if(row.document_type==='ORDER' && row.status==='RELEASED') {
    for(const combined of [false,true]) action(combined?'Post Receive & Invoice':'Post Receive · Nhận hàng',async()=>{
      const lines=[];for(let page=0;;page++){const data=await erpGet('/tables/purchase_lines?size=100&page='+page+'&filterColumn=purchase_header_id&filterValue='+row.id);lines.push(...data.rows);if(data.rows.length<100)break;}
      const available=lines.filter(l=>Number(l.quantity)>Number(l.quantity_received));
      if(!available.length) throw new Error('Đơn đã nhận đủ hàng.');
      purchaseWorkflowForm(combined?'Nhận hàng và đăng hóa đơn đồng thời':'Số lượng thực nhận (nhập 0 cho dòng chưa nhận)',[['postingDate','Posting Date',purchaseToday(),'date'],...(combined?[['vendorInvoiceNo','Vendor Invoice No.','']]:[]),...available.map(l=>['line_'+l.id,`${l.item_no || l.description} · Kho ${l.location_code || 'chưa chọn'} · còn ${Number(l.quantity)-Number(l.quantity_received)}`,0,'number'])],async(values,requestKey)=>{
        const lines=available.filter(l=>Number(values['line_'+l.id])>0).map(l=>({lineId:l.id,quantity:values['line_'+l.id]}));
        const receipt={requestKey,postingDate:values.postingDate,lines};
        const result=await purchaseWorkflowRequest(row.id+(combined?'/receive-invoice':'/receive'),combined?{receipt,vendorInvoiceNo:values.vendorInvoiceNo}:receipt);await purchaseReload(combined?result.id:row.id);
      });
    });
    action('Tạo Purchase Invoice',()=>{document.getElementById('erp-detail-dialog').close();return openErpForm('purchase_headers',null,null,'INVOICE');});
  }
  if(row.document_type==='INVOICE' && row.status==='DRAFT') action('Post Invoice · Đăng hóa đơn',()=>purchaseWorkflowForm('Đăng hóa đơn và công nợ',[['postingDate','Posting Date',purchaseToday(),'date']],async values=>{
    await purchaseWorkflowRequest(row.id+'/post-invoice',values);await purchaseReload(row.id);
  }));
  if(row.document_type==='RETURN_ORDER' && row.status==='RELEASED') action('Post Return Shipment · Gửi trả hàng',()=>purchaseWorkflowForm('Gửi trả hàng: trừ tồn kho, chưa giảm công nợ',[['postingDate','Posting Date',purchaseToday(),'date']],async values=>{
    await purchaseWorkflowRequest(row.id+'/ship-return',values);await purchaseReload(row.id);
  }));
  if(row.document_type==='INVOICE' && row.status==='POSTED') {
    action('Nhận hoàn tiền',()=>purchaseWorkflowForm('Nhà cung cấp hoàn tiền sau Credit Memo',[['postingDate','Posting Date',purchaseToday(),'date'],['amount','Số tiền nhận lại (VND)','','number'],['reference','Mã giao dịch / tham chiếu','']],async(values,requestKey)=>{
      await purchaseWorkflowRequest(row.id+'/refund',{...values,requestKey});await purchaseReload(row.id);
    }));
    action('Thanh toán',()=>purchaseWorkflowForm('Thanh toán nhà cung cấp',[['postingDate','Posting Date',purchaseToday(),'date'],['amount','Số tiền (VND)','','number'],['reference','Mã giao dịch / tham chiếu','']],async(values,requestKey)=>{
      await purchaseWorkflowRequest(row.id+'/pay',{...values,requestKey});await purchaseReload(row.id);
    }));
    action('Purchase Credit Memo',async()=>{
      const returns=[];for(let page=0;;page++){const data=await erpGet('/tables/purchase_headers?size=100&page='+page+'&filterColumn=document_type&filterValue=RETURN_ORDER');returns.push(...data.rows.filter(r=>r.status==='POSTED' && String(r.vendor_id)===String(row.vendor_id) && r.currency_code===row.currency_code));if(data.rows.length<100)break;}
      purchaseWorkflowForm('Giảm công nợ (không tự xuất trả kho)',[['postingDate','Posting Date',purchaseToday(),'date'],['amount','Tổng giảm gồm VAT','','number'],['vatAmount','VAT giảm',0,'number'],['reference','Số Credit Memo nhà cung cấp / lý do',''],['returnNo','Số Return Order đã gửi trả (bỏ trống nếu chỉ giảm giá)','']],async(values,requestKey)=>{
        const match=values.returnNo.trim()?returns.find(r=>r.document_no===values.returnNo.trim()):null;
        if(values.returnNo.trim() && !match) throw new Error('Return Order phải đã gửi trả và cùng nhà cung cấp/tiền tệ.');
        await purchaseWorkflowRequest(row.id+'/credit',{requestKey,postingDate:values.postingDate,amount:values.amount,vatAmount:values.vatAmount,reference:values.reference,returnOrderId:match?.id || null});await purchaseReload(row.id);
      });
    });
  }
  try {
    const history=await erpGet('/purchase-workflow/'+row.id+'/history');
    if(!area.isConnected)return;
    if(row.document_type==='INVOICE')area.append(erpElement('p',`Công nợ đã đăng sổ còn lại: ${erpValue(history.balance,'total_amount')} ${row.currency_code}`));
    const groups=[['Phiếu nhập đã đăng',history.receipts,['receipt_no','receipt_date']],['Sổ công nợ nhà cung cấp',history.entries,['document_no','entry_type','posting_date','amount','reference']],['Sổ cái G/L',history.gl,['document_no','posting_date','account_no','account_name','debit','credit']],['Giá trị hàng (Expected / Actual / Return)',history.values,['entry_type','quantity','amount','currency_code','posting_date']]];
    for(const [title,rows,columns] of groups)if(rows.length){
      const section=erpElement('details',null,'document-section');section.append(erpElement('summary',title));
      const scroll=erpElement('div',null,'sales-lines-scroll');const table=erpElement('table');const head=erpElement('tr');columns.forEach(c=>head.append(erpElement('th',columnLabels[c]||c)));table.append(head);
      rows.forEach(r=>{const tr=erpElement('tr');columns.forEach(c=>tr.append(erpElement('td',erpValue(r[c],c))));table.append(tr);});scroll.append(table);section.append(scroll);area.append(section);
    }
  }catch(e){error.textContent=e.message;}
}
