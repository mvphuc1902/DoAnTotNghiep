async function salesAccountingActions(host,table,row){
 const id=table==='sales_invoices'?row.id:table==='sales_headers'&&row.document_type==='INVOICE'?row.sales_invoice_id:null;if(!id)return;
 const section=erpElement('section',null,'profile-section');host.append(section);
 try{
  const state=await erpGet('/sales-accounting/'+id);if(!section.isConnected)return;const h=state.invoice;
  section.append(erpElement('h3','Công nợ hóa đơn bán'));
  if(!h.posted_at){section.append(erpElement('p','Chứng từ lịch sử chưa ghi sổ qua luồng mới. Không tự ghi lại để tránh trùng công nợ.','document-hint'));return;}
  section.append(erpElement('p','Đã ghi sổ khi lưu · Công nợ còn lại: '+erpDecimal(state.balance,true)+' VND','document-hint'));
  const actions=erpElement('div',null,'profile-actions'),error=erpElement('p','','text-rose-600');section.append(actions,error);
  const refresh=async()=>{section.remove();await salesAccountingActions(host,table,row);};
  if(Number(state.balance)>0&&['ADMIN','DIRECTOR'].includes(currentUser?.role))actions.append(erpButton('Ghi nhận thanh toán',()=>purchaseWorkflowForm('Thu tiền hóa đơn '+h.invoice_no,[['postingDate','Ngày thu tiền',purchaseToday(),'date'],['amount','Số tiền thu',state.balance,'number'],['reference','Nội dung thu tiền','Thu tiền '+h.invoice_no,'text']],async(values,requestKey)=>{const response=await fetch(erpApi+'/sales-accounting/'+id+'/pay',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({...values,requestKey})});const body=await response.json().catch(()=>({}));if(!response.ok)throw Error(body.message||'Không ghi nhận được thanh toán.');await refresh();})));
  actions.append(erpButton('Customer Ledger',()=>openErpTable('customer_ledger','customer_id',h.customer_id)),erpButton('G/L · '+h.invoice_no,()=>openErpTable('general_ledger','document_no',h.invoice_no)));
  state.payments.forEach(p=>actions.append(erpButton(p.payment_no,()=>openErpTable('payments','id',p.id))));
  host.querySelectorAll('.profile-actions button').forEach(button=>{if(/^(Sửa|Chỉnh sửa|Xóa)/.test(button.textContent)){button.disabled=true;button.title='Hóa đơn đã ghi sổ khi lưu; không sửa/xóa trực tiếp.';}});
 }catch(e){section.append(erpElement('p',e.message,'text-rose-600'));}
}
