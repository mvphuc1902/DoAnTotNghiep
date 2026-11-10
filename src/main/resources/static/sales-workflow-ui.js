async function salesShippingActions(detail,table,row) {
  const id=table==='sales_orders'?row.id:row.document_type==='ORDER'?row.sales_order_id:null;
  if(!id)return;
  const area=erpElement('section',null,'profile-section');detail.append(area);
  try {
    const state=await erpGet('/sales-shipping/'+id);
    if(!area.isConnected)return;
    if(!state.deferred){area.append(erpElement('p','Đơn cũ: tồn kho đã được xử lý theo luồng trước đây. Không xuất kho lại.','document-hint'));return;}
    const bar=erpElement('div',null,'profile-actions'),error=erpElement('p','','text-rose-600');error.setAttribute('role','alert');area.append(bar,error);
    area.append(erpElement('p',state.released?'Đã Release · Xuất kho khi Post Shipment.':'Chưa Release · Đơn hàng chưa làm thay đổi tồn kho.','document-hint'));
    async function post(action,body){const r=await fetch(erpApi+'/sales-shipping/'+id+'/'+action,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body||{})});const data=await r.json().catch(()=>({}));if(!r.ok)throw Error(data.message||'Không thực hiện được thao tác. Kiểm tra trạng thái, số lượng và tồn kho.');return data;}
    function action(label,fn){bar.append(erpButton(label,async event=>{event.currentTarget.disabled=true;try{await fn();}catch(e){error.textContent=e.message;}finally{event.currentTarget.disabled=false;}}));}
    if(!state.released)action('Release',async()=>{await post('release');area.remove();await salesShippingActions(detail,table,row);});
    else action('Post Shipment',()=>purchaseWorkflowForm('Giao hàng · Nhập số lượng thực xuất',[['postingDate','Ngày giao hàng',purchaseToday(),'date'],...state.lines.filter(l=>Number(l.quantity)>Number(l.quantity_shipped)).map(l=>['line_'+l.id,`${l.item_no} · còn ${Number(l.quantity)-Number(l.quantity_shipped)}`,0,'number'])],async(values,requestKey)=>{
      const lines=state.lines.filter(l=>Number(values['line_'+l.id])>0).map(l=>({lineId:l.id,quantity:values['line_'+l.id]}));
      await post('ship',{requestKey,postingDate:values.postingDate,lines});area.remove();await salesShippingActions(detail,table,row);
    }));
    if(state.lines.some(l=>Number(l.quantity_shipped)>Number(l.invoiced)))action('Tạo Sales Invoice',()=>openErpForm('sales_invoices'));
    for(const shipment of state.shipments)area.append(erpButton(shipment.shipment_no,()=>openErpTable('sales_shipments','id',shipment.id)));
  }catch(e){area.append(erpElement('p',e.message,'text-rose-600'));}
}
function scopeSalesFields(form,type) {
  const main=new Set(['customer_id','sales_order_id','location_id','order_date','invoice_date','return_date','currency_code']);
  if(type==='INVOICE') ['posting_date','payment_terms_code','due_date'].forEach(n=>main.add(n));
  if(type==='ORDER')main.add('requested_delivery_date');
  if(type==='RETURN_ORDER')main.add('reason');
  if(type==='QUOTE')main.add('quote_valid_until_date');
  const system=new Set(['id','document_type','created_at','updated_at','sales_invoice_id','sales_return_id','related_sales_order_id','archived_versions']);
  for(const section of form.querySelectorAll('.document-section')){
    const fields=section.querySelector(':scope > .document-fields');if(!fields)continue;
    const optional=[];
    for(const label of fields.children){const input=label.querySelector('[name]');if(!input)continue;label.classList.remove('document-field-collapsed');if(system.has(input.name)){label.style.setProperty('display','none','important');continue;}if(!main.has(input.name)&&!input.required)optional.push(label);}
    const summary=section.querySelector('summary');summary.querySelector('.document-fields-toggle')?.remove();
    if(!optional.length)continue;
    const button=erpElement('button','','document-fields-toggle');button.type='button';
    const expand=state=>{optional.forEach(l=>l.classList.toggle('document-field-collapsed',!state));button.textContent=state?'Show less':'Show more';button.setAttribute('aria-expanded',String(state));section.open=true;};
    button.onclick=e=>{e.preventDefault();e.stopPropagation();expand(button.getAttribute('aria-expanded')!=='true');};section.documentExpandFields=()=>expand(true);summary.append(button);expand(false);
  }
}
