async function hrEmailBatch(rows) {
  if (!hrPayrollManager() || !rows.length) return;
  const dialog=hrDialog('Gửi nhiều phiếu lương'),content=hrNode('div',null,'hr-form');
  const progress=hrNode('p','Đang kiểm tra người nhận…','hr-subtitle');progress.setAttribute('role','status');
  const list=hrNode('div',null,'payroll-email-list');
  const close=profileAction('Đóng',()=>dialog.close());
  const send=profileAction('Xác nhận gửi email',sendAll,'primary');send.disabled=true;
  const actions=hrNode('div',null,'hr-actions');actions.append(close,send);
  content.append(hrNode('h2','Gửi phiếu lương đã chọn'),hrNode('p','Mỗi nhân viên nhận một email riêng. Lựa chọn bao gồm các phiếu đã tick ở trang hoặc bộ lọc khác.','hr-subtitle'),progress,list,actions);dialog.append(content);dialog.showModal();
  let sending=false,started=false;
  dialog.addEventListener('cancel',event=>{if(sending)event.preventDefault();});
  const entries=rows.map(row=>{
    const card=hrNode('div',null,'payroll-email-entry'),status=hrNode('p','Đang kiểm tra…','hr-subtitle');status.setAttribute('role','status');
    card.append(hrNode('strong',`${row.employee_name} · ${hrDate(row.pay_month).slice(0,7)}`),status);list.append(card);
    return {row,status,preview:null};
  });
  for(const entry of entries){
    if(!dialog.open)return;
    try{
      const preview=await hrApi(`payroll/${entry.row.id}/email`);
      if(!preview.configured)throw new Error(preview.configurationMessage||'Chưa cấu hình máy chủ email.');
      entry.preview=preview;entry.status.textContent=`${preview.recipient} · ${preview.subject}`;
    }catch(error){entry.status.textContent=error.message;entry.status.classList.add('hr-error');}
  }
  if(!dialog.open)return;
  const ready=entries.filter(entry=>entry.preview);
  progress.textContent=`${ready.length}/${entries.length} phiếu có thể gửi. Phiếu lỗi kiểm tra sẽ được bỏ qua.`;
  send.disabled=!ready.length;send.textContent=`Gửi ${ready.length} phiếu lương`;
  async function sendAll(){
    if(sending||started)return;
    sending=true;started=true;send.disabled=true;close.disabled=true;let sent=0,failed=0;
    // Individual authorized requests retain recipient/version checks and duplicate protection.
    for(const entry of entries.filter(entry=>entry.preview)){
      entry.status.textContent=`Đang gửi đến ${entry.preview.recipient}…`;
      try{
        const result=await hrApi(`payroll/${entry.row.id}/email`,'POST',{recipient:entry.preview.recipient,version:entry.preview.version});
        entry.status.textContent=`${entry.preview.recipient}: ${result?.message||'Máy chủ email đã tiếp nhận.'}`;sent++;
      }catch(error){entry.status.textContent=`${entry.preview.recipient}: ${error.message}`;entry.status.classList.add('hr-error');failed++;}
      progress.textContent=`Đã xử lý ${sent+failed}/${ready.length} · Thành công: ${sent} · Lỗi/chưa xác nhận: ${failed}`;
    }
    sending=false;close.disabled=false;send.textContent='Đã xử lý';
    progress.textContent+=` · Bỏ qua: ${entries.length-ready.length}.`;
    if(hrState.section==='payroll')await openHr('payroll');
  }
}
