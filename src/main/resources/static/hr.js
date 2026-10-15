const hrTitles = {leaves:'Leave Requests', tasks:'Tasks', payroll:'Payroll'};
function renderHrOnlyNavigation() {
  const nav=document.getElementById('erp-navigation');nav.replaceChildren();
  nav.append(hrNode('p','HUMAN RESOURCES','px-3 py-2 text-xs font-semibold text-slate-400'));
  [['leaves','Leave Requests'],['payroll','Payroll'],['employees','Employees'],['posts','Recruitment & News & Announcements']].forEach(([key,label])=>{
    const button=profileAction(label,()=>openHr(key));button.id='nav-hr-'+key;
    button.className='nav-btn w-full text-left px-3 py-2 rounded-lg hover:bg-slate-800';nav.append(button);
  });
  document.getElementById('erp-table-search').parentElement.hidden=true;
  document.getElementById('erp-global-search').parentElement.hidden=true;
}
const hrLabels = {ANNUAL:'Annual leave',UNPAID:'Unpaid leave',SICK:'Sick leave',OTHER:'Other',PENDING:'Pending',APPROVED:'Approved',REJECTED:'Rejected',CANCELLED:'Cancelled',TODO:'To do',IN_PROGRESS:'In progress',DONE:'Done',LOW:'Low',NORMAL:'Normal',HIGH:'High',URGENT:'Urgent',MONTHLY:'Monthly / paid days',HOURLY:'Hourly',TASK:'Per task'};
let hrState = {section:'leaves',rows:[],people:[],version:0};
const hrManager = () => ['ADMIN','DIRECTOR'].includes(currentUser?.role);
const hrPayrollManager = () => ['ADMIN','DIRECTOR','HR'].includes(currentUser?.role);
const hrCanAssign = () => ['ADMIN','DIRECTOR','MANAGER','LEADER'].includes(currentUser?.role);
const hrCanManageTask = row => hrManager() || hrCanAssign() && hrState.people.some(p=>String(p.id)===String(row.assigned_to));
const hrNode = (tag,text,classes='') => profileElement(tag,text,classes);
const hrMoney = value => erpDecimal(value,true);
const hrOwn = id => String(id) === String(currentUser.id);
const hrDate = value => String(value || '').slice(0,10);
async function hrApi(path, method='GET', body) {
  const multipart = body instanceof FormData;
  const response = await fetch('/api/hr/' + path,{method,headers:multipart?{}:{'Content-Type':'application/json'},...(body === undefined ? {} : {body:multipart?body:JSON.stringify(body)})});
  if (!response.ok) {
    const error = await response.json().catch(()=>({}));
    throw new Error(error.message || (response.status===403?'You do not have permission for this action.':`Request failed (${response.status}).`));
  }
  if(method!=='GET' && /^(posts|recruitment)(\/|$)/.test(path))document.dispatchEvent(new Event('hr-content-changed'));
  return response.status===204 || response.headers.get('content-length')==='0' ? null : response.text().then(text=>text?JSON.parse(text):null);
}
async function openHr(section) {
  if(currentUser?.role==='HR' && section==='tasks')return;
  if(section==='recruitment')return openRecruitment();
  if(section==='employees')return openHrEmployees();
  if(section==='posts')return openHrPosts();
  if (!hrTitles[section]) return;
  hrState.section=section; const version=++hrState.version;
  switchTab('hr');
  const nav=document.getElementById('nav-hr-'+section); nav?.classList.add('active'); if(nav?.closest('details'))nav.closest('details').open=true;
  const root=document.getElementById('tab-hr'); root.replaceChildren(hrNode('p','Loading '+hrTitles[section]+'…','hr-subtitle'));
  try {
    const [rows,people]=await Promise.all([hrApi(section),hrApi(section==='tasks'?'task-people':'people')]);
    if(version!==hrState.version)return;
    Object.assign(hrState,{rows,people}); renderHr();
  } catch(error) { if(version===hrState.version)root.replaceChildren(hrNode('p',error.message,'hr-error'),profileAction('Retry',()=>openHr(section))); }
}
function hrBadge(value) { const badge=hrNode('span',hrLabels[value]||value,'hr-badge');badge.dataset.status=value;return badge; }
function renderHr() {
  const {section,rows}=hrState, root=document.getElementById('tab-hr');root.replaceChildren();
  const heading=hrNode('div',null,'hr-heading'), intro=hrNode('div');
  intro.append(hrNode('p','HUMAN RESOURCES','profile-eyebrow'),hrNode('h2',hrTitles[section]));
  const subtitles={leaves:'Submit a leave request and track its approval. Pending requests can be cancelled. Approval is handled by another Admin or Director.',tasks:'Managers assign work to department Leaders. Leaders assign work to employees in their team. Assignees update work notes, progress and deadlines.',payroll:'Salary worksheets in VND. Paid days, hours or completed task units are entered and verified by HR / Admin / Director. Taxes and insurance are entered as deductions; they are not calculated automatically.'};
  if(currentUser?.role==='HR')subtitles.leaves='Bạn có thể đăng ký nghỉ cho chính mình và xem đơn của mọi người. Chỉ được sửa hoặc hủy đơn của mình khi đang chờ duyệt; không được sửa, duyệt hoặc xóa đơn của người khác.';
  intro.append(hrNode('p',subtitles[section],'hr-subtitle'));heading.append(intro);
  if(section==='leaves'||section==='tasks'&&hrCanAssign()||section==='payroll'&&hrPayrollManager())heading.append(profileAction({leaves:'+ Request leave',tasks:'+ New task',payroll:'+ Create payroll'}[section],()=>hrForm(section),'primary'));
  root.append(heading);
  if(section==='payroll'&&hrPayrollManager())root.append(hrNode('p','Tạo phiếu lương → tick chọn các phiếu → Gửi email đã chọn → kiểm tra người nhận và xác nhận gửi.','hr-formula'));
  const summary=hrNode('div',null,'hr-summary');
  const stats=section==='leaves'?[['Requests',rows.length],['Pending',rows.filter(r=>r.status==='PENDING').length],['Approved',rows.filter(r=>r.status==='APPROVED').length]]:section==='tasks'?[['Tasks',rows.length],['In progress',rows.filter(r=>r.status==='IN_PROGRESS').length],['Completed',rows.filter(r=>r.status==='DONE').length]]:[['Salary worksheets',rows.length],['Accounts',new Set(rows.map(r=>r.user_id)).size],['Latest month',rows[0]?.pay_month?.slice(0,7)||'—']];
  stats.forEach(([label,value])=>{const card=hrNode('div',null,'hr-stat');card.append(hrNode('span',label),hrNode('strong',String(value)));summary.append(card);}); root.append(summary);
  const toolbar=hrNode('div',null,'hr-toolbar'), search=hrNode('input',null,'hr-input'), filter=hrNode('select',null,'hr-input');
  search.type='search';search.placeholder='Search name, title or month…';search.setAttribute('aria-label','Search HR records');
  filter.setAttribute('aria-label','Filter records');const values=section==='leaves'?['PENDING','APPROVED','REJECTED','CANCELLED']:section==='tasks'?['TODO','IN_PROGRESS','DONE','CANCELLED']:['MONTHLY','HOURLY','TASK'];
  [['','All '+(section==='payroll'?'methods':'statuses')],...values.map(v=>[v,hrLabels[v]])].forEach(([value,label])=>{const o=hrNode('option',label);o.value=value;filter.append(o);});
  toolbar.append(search,filter,profileAction('Refresh',()=>openHr(section)));root.append(toolbar);
  const batchEnabled=section==='payroll'&&hrPayrollManager(), selected=new Map();
  const sendSelected=profileAction('Gửi email đã chọn (0)',()=>hrEmailBatch([...selected.values()]),'primary');
  const copySelected=profileAction('Tạo lương tháng sau (0)',async()=>{
    const chosen=[...selected.values()];if(!chosen.length)return;
    const periods=[...new Set(chosen.map(row=>{const [year,month]=hrDate(row.pay_month).split('-').map(Number);return `${year+(month===12?1:0)}-${String(month===12?1:month+1).padStart(2,'0')}`;}))];
    if(!await confirmAction(`Tạo ${chosen.length} phiếu cho tháng ${periods.join(', ')} từ các phiếu đã chọn? Toàn bộ thông tin lương, ngày công, thưởng và khấu trừ sẽ được sao chép. Phiếu đã tồn tại sẽ được bỏ qua.`,{title:'Tạo lương tháng sau',confirmText:'Tạo phiếu lương',danger:false}))return;
    copySelected.disabled=true;
    try{const result=await hrApi('payroll/copy-next-month','POST',{ids:chosen.map(row=>row.id)});await openHr('payroll');notifyCustomer(`Đã tạo ${result.created} phiếu tháng sau; bỏ qua ${result.skipped} phiếu đã tồn tại. Kiểm tra lại ngày công, thưởng và khấu trừ trước khi gửi.`);}
    catch(error){notifyCustomer(error.message);copySelected.disabled=false;}
  });
  const clearSelected=profileAction('Bỏ chọn tất cả',()=>{selected.clear();fill();});
  const syncSelection=()=>{sendSelected.textContent=`Gửi email đã chọn (${selected.size})`;sendSelected.disabled=!selected.size;copySelected.textContent=`Tạo lương tháng sau (${selected.size})`;copySelected.disabled=!selected.size;clearSelected.disabled=!selected.size;};
  if(batchEnabled){toolbar.append(sendSelected,copySelected,clearSelected);syncSelection();}
  const wrap=hrNode('div',null,'hr-table-wrap'),table=hrNode('table',null,'hr-table'),thead=hrNode('thead'),tr=hrNode('tr'),tbody=hrNode('tbody');
  const columns=section==='leaves'?['Employee','Leave type','From','To','Status','']:section==='tasks'?['Task','Assigned to','Team','Deadline','Progress','Status','']:['Employee','Month','Method','Gross pay','Net pay',''];
  const selectPage=hrNode('input',null,'payroll-checkbox');selectPage.type='checkbox';selectPage.setAttribute('aria-label','Chọn tất cả phiếu trên trang này');
  if(batchEnabled){const cell=hrNode('th');cell.append(selectPage);tr.append(cell);}
  columns.forEach(c=>tr.append(hrNode('th',c)));thead.append(tr);table.append(thead,tbody);wrap.append(table);root.append(wrap);
  const pagination=hrNode('div',null,'hr-toolbar'); root.append(pagination);let page=0;
  function fill(reset=false) {
    if(reset)page=0;tbody.replaceChildren();
    const matches=rows.filter(r=>(!filter.value||(r.status||r.method)===filter.value)&&Object.values(r).join(' ').toLowerCase().includes(search.value.toLowerCase()));
    const visible=matches.slice(page*20,(page+1)*20);
    const updatePage=()=>{selectPage.checked=visible.length>0&&visible.every(row=>selected.has(String(row.id)));selectPage.indeterminate=visible.some(row=>selected.has(String(row.id)))&&!selectPage.checked;selectPage.disabled=!visible.length;syncSelection();};
    selectPage.onchange=()=>{visible.forEach(row=>{if(selectPage.checked)selected.set(String(row.id),row);else selected.delete(String(row.id));});fill();};
    visible.forEach(row=>{
      const line=hrNode('tr');const values=section==='leaves'?[row.employee_name,hrLabels[row.leave_type],hrDate(row.start_date),hrDate(row.end_date),hrBadge(row.status)]:section==='tasks'?[row.title,row.employee_name,row.team||'—',hrDate(row.due_date),String(row.progress||0)+'%',hrBadge(row.status)]:[row.employee_name,hrDate(row.pay_month).slice(0,7),hrLabels[row.method],hrMoney(row.gross_pay),hrMoney(row.net_pay)];
      if(batchEnabled){const cell=hrNode('td'),check=hrNode('input',null,'payroll-checkbox');check.type='checkbox';check.checked=selected.has(String(row.id));check.setAttribute('aria-label',`Chọn phiếu lương ${row.employee_name} tháng ${hrDate(row.pay_month).slice(0,7)}`);check.onchange=()=>{if(check.checked)selected.set(String(row.id),row);else selected.delete(String(row.id));updatePage();};cell.append(check);line.append(cell);}
      values.forEach(value=>{const td=hrNode('td');if(value instanceof Node)td.append(value);else td.textContent=value;line.append(td);});
      const actions=hrNode('td');actions.append(profileAction('View',()=>hrDetail(section,row)));line.append(actions);tbody.append(line);
    });
    if(!matches.length){const line=hrNode('tr'),cell=hrNode('td','No records found.','hr-empty');cell.colSpan=columns.length+(batchEnabled?1:0);line.append(cell);tbody.append(line);}
    if(batchEnabled)updatePage();
    const prev=profileAction('Previous',()=>{page--;fill();}),next=profileAction('Next',()=>{page++;fill();});prev.disabled=page===0;next.disabled=(page+1)*20>=matches.length;
    pagination.replaceChildren(prev,hrNode('span',`${matches.length} records · Page ${page+1} of ${Math.max(1,Math.ceil(matches.length/20))}`,'hr-subtitle'),next);
  }
  search.oninput=()=>fill(true);filter.onchange=()=>fill(true);fill();
  root.append(hrNode('p','Showing up to your latest 500 accessible records.','hr-subtitle'));
}
function hrDialog(title) {
  const dialog=hrNode('dialog',null,'hr-dialog');dialog.setAttribute('aria-label',title);document.body.append(dialog);dialog.addEventListener('close',()=>dialog.remove(),{once:true});return dialog;
}
function hrDetail(section,row) {
  const dialog=hrDialog(hrTitles[section]),close=hrNode('div',null,'profile-close');close.append(profileAction('Close',()=>dialog.close()));dialog.append(close);
  dialog.append(profileHeader(hrTitles[section],section==='tasks'?row.title:row.employee_name,'Record #'+row.id,hrLabels[row.status||row.method]));
  let fields;
  if(section==='leaves')fields=[['Employee',row.employee_name],['Leave type',hrLabels[row.leave_type]],['Start date',hrDate(row.start_date)],['End date',hrDate(row.end_date)],['Reason',row.reason],['Reviewer',row.reviewer_name||'—'],['Review note',row.review_note||'—'],['Reviewed at',row.reviewed_at||'—']];
  else if(section==='tasks')fields=[['Assigned to',row.employee_name],['Created by',row.creator_name],['Due date',hrDate(row.due_date)],['Priority',hrLabels[row.priority]],['Description',row.description||'—'],['Team',row.team||'—'],['Progress',String(row.progress||0)+'%'],['Work update',row.work_note||'—'],['Updated at',row.updated_at]];
  else fields=[['Month',hrDate(row.pay_month).slice(0,7)],['Rate',hrMoney(row.base_rate)],['Standard days',row.standard_days],['Paid days / hours / task units',row.units],['Overtime hours',row.overtime_hours],['Overtime hourly rate',hrMoney(row.overtime_rate)],['Allowance',hrMoney(row.allowance)],['Bonus',hrMoney(row.bonus)],['Deductions',hrMoney(row.deductions)],['Gross pay',hrMoney(row.gross_pay)],['Net pay',hrMoney(row.net_pay)],['Notes',row.notes||'—']];
  dialog.append(profileSection('Record details',fields));
  if(section==='payroll')dialog.append(profileSection('Email delivery',[
    ['Current version',row.email_status==='SENT'?'Accepted by mail server':row.email_status==='FAILED'?'Sending failed / unconfirmed':'Not sent'],
    ['Last sent to',row.email_recipient||'—'],['Last accepted at',row.email_sent_at||'—']
  ]));
  const actions=hrNode('div',null,'profile-actions'),error=hrNode('p','','hr-error');
  const mutate=async(path,method,body)=>{actions.querySelectorAll('button').forEach(b=>b.disabled=true);try{await hrApi(path,method,body);dialog.close();await openHr(section);}catch(e){error.textContent=e.message;actions.querySelectorAll('button').forEach(b=>b.disabled=false);}};
  if(section==='leaves'&&row.status==='PENDING') {
    if(hrOwn(row.user_id))actions.append(profileAction('Sửa đơn của tôi',()=>{dialog.close();hrForm('leaves',row);},'primary'));
    if(hrOwn(row.user_id))actions.append(profileAction('Cancel request',async()=>{if(await confirmAction('Hủy yêu cầu nghỉ phép này?', {title:'Hủy yêu cầu', confirmText:'Hủy yêu cầu', danger:false}))mutate(`leaves/${row.id}/status`,'PUT',{status:'CANCELLED',note:''});}));
    else if(hrManager()) {
      const note=hrNode('textarea',null,'hr-input');note.placeholder='Review note (optional)';note.maxLength=2000;note.setAttribute('aria-label','Review note');actions.append(note);
      ['APPROVED','REJECTED'].forEach(status=>actions.append(profileAction(status==='APPROVED'?'Approve':'Reject',()=>mutate(`leaves/${row.id}/status`,'PUT',{status,note:note.value}),status==='APPROVED'?'primary':'')));
    }
  }
  if(section==='tasks'&&(hrOwn(row.assigned_to)||hrCanManageTask(row))||section==='payroll'&&hrPayrollManager())actions.append(profileAction('Edit',()=>{dialog.close();hrForm(section,row);},'primary'));
  if(section==='payroll'&&hrPayrollManager())actions.append(profileAction('Email payslip',()=>{dialog.close();hrEmail(row);}));
  if(hrManager()&&(section!=='leaves'||['CANCELLED','REJECTED'].includes(row.status)))actions.append(profileAction('Delete',async()=>{if(await confirmAction('Xóa vĩnh viễn bản ghi này?'))mutate(`${section}/${row.id}`,'DELETE');},'danger'));
  dialog.append(actions,error);enhanceDocumentForm(dialog);dialog.showModal();
}
function hrForm(section,row=null) {
  const title=(row?'Edit ':'New ')+({leaves:'leave request',tasks:'task',payroll:'salary worksheet'}[section]);
  const dialog=hrDialog(title),form=hrNode('form',null,'hr-form'),grid=hrNode('div',null,'hr-fields'),error=hrNode('p','','hr-error'),controls={};
  error.setAttribute('role','alert');form.append(hrNode('h2',title),hrNode('p','Fields marked * are required.','hr-subtitle'),grid);dialog.append(form);
  function field(name,label,type,value,options=null,wide=false) {
    const wrapper=hrNode('label',null,'hr-field'+(wide?' wide':''));wrapper.append(hrNode('span',label));
    const input=hrNode(options?'select':type==='textarea'?'textarea':'input',null,'hr-input');
    if(options)options.forEach(([id,text])=>{const option=hrNode('option',text);option.value=id;input.append(option);});else if(type!=='textarea')input.type=type;
    input.name=name;input.required=label.endsWith('*');input.value=value??'';
    if(type==='number'){input.min='0';input.max='999999999';input.step='0.01';}
    wrapper.append(input);grid.append(wrapper);controls[name]=input;return input;
  }
  const options=values=>values.map(v=>[v,hrLabels[v]]);
  const people=hrState.people.map(p=>[p.id,`${p.name||p.username} (${p.username})`]);
  if(section==='leaves') {
    field('startDate','Start date *','date',hrDate(row?.start_date));field('endDate','End date *','date',hrDate(row?.end_date));
    field('leaveType','Leave type *','select',row?.leave_type||'ANNUAL',options(['ANNUAL','UNPAID','SICK','OTHER']));
    field('reason','Reason *','textarea',row?.reason||'',null,true).maxLength=2000;
    controls.endDate.min=controls.startDate.value;
    controls.startDate.onchange=()=>{controls.endDate.min=controls.startDate.value;};
  } else if(section==='tasks') {
    field('title','Task title *','text',row?.title,null,true).maxLength=200;
    if(row&&!people.some(p=>String(p[0])===String(row.assigned_to)))people.push([row.assigned_to,row.employee_name]);
    field('assignedTo','Assigned to *','select',row?.assigned_to||people[0]?.[0]||'',people);
    field('dueDate','Due date *','date',hrDate(row?.due_date));
    field('priority','Priority *','select',row?.priority||'NORMAL',options(['LOW','NORMAL','HIGH','URGENT']));
    field('status','Status *','select',row?.status||'TODO',options(['TODO','IN_PROGRESS','DONE','CANCELLED']));
    field('description','Description','textarea',row?.description||'',null,true).maxLength=4000;
    field('progress','Progress (%) *','number',row?.progress||0);controls.progress.max='100';controls.progress.step='1';
    field('workNote','Work update','textarea',row?.work_note||'',null,true).maxLength=4000;
    controls.status.onchange=()=>{if(controls.status.value==='DONE')controls.progress.value=100;if(controls.status.value==='TODO')controls.progress.value=0;};
    controls.progress.oninput=()=>{const n=Number(controls.progress.value);controls.status.value=n===100?'DONE':n===0?'TODO':'IN_PROGRESS';};
    if(row&&!hrCanManageTask(row)) {
      ['assignedTo','title','description','priority'].forEach(key=>controls[key].disabled=true);
      [...controls.status.options].find(o=>o.value==='CANCELLED').disabled=true;
    }
    if(!row&&!people.length)form.append(hrNode('p','No eligible assignee. Ask Admin to set the role, department and team on user accounts.','hr-error'));
  } else {
    field('userId','Employee account *','select',row?.user_id||currentUser.id,people);
    const today=new Date();
    field('month','Payroll month *','month',row?hrDate(row.pay_month).slice(0,7):`${today.getFullYear()}-${String(today.getMonth()+1).padStart(2,'0')}`);
    field('method','Calculation method *','select',row?.method||'MONTHLY',options(['MONTHLY','HOURLY','TASK']));
    field('baseRate','Monthly salary / hourly rate / task rate (VND) *','number',row?.base_rate||0);
    field('standardDays','Standard working days *','number',row?.standard_days||26);controls.standardDays.max='31';controls.standardDays.min='0.01';
    field('units','Paid days / worked hours / completed task units *','number',row?.units||0);controls.units.max='99999';
    field('overtimeHours','Overtime hours *','number',row?.overtime_hours||0);controls.overtimeHours.max='744';
    field('overtimeRate','Overtime hourly rate (VND) *','number',row?.overtime_rate||0);
    field('allowance','Allowance (VND) *','number',row?.allowance||0);
    field('bonus','Bonus (VND) *','number',row?.bonus||0);
    field('deductions','Total deductions (VND) *','number',row?.deductions||0);
    field('notes','Notes / deduction breakdown','textarea',row?.notes||'',null,true).maxLength=2000;
    const explanation=hrNode('p','','hr-formula');form.append(explanation);
    const explain=()=>{explanation.textContent=({MONTHLY:'Base pay = monthly salary × paid days ÷ standard working days.',HOURLY:'Base pay = hourly rate × worked hours.',TASK:'Base pay = task rate × verified completed task units.'}[controls.method.value])+'\nGross pay = base pay + overtime hours × overtime rate + allowance + bonus.\nNet pay = gross pay − deductions.\nPaid leave and attendance are not imported automatically. Enter verified paid days and deductions.';};
    controls.method.onchange=explain;explain();
    const preview=hrNode('p','','hr-formula');preview.hidden=true;preview.setAttribute('role','status');form.append(preview);
    const calculate=profileAction('Preview calculation',async()=>{if(!form.reportValidity())return;calculate.disabled=true;error.textContent='';try{const result=await hrApi('payroll/preview','POST',payload());preview.textContent=`Gross pay: ${hrMoney(result.gross)}\nNet pay: ${hrMoney(result.net)}`;preview.hidden=false;}catch(e){error.textContent=e.message;}finally{calculate.disabled=false;}});form.append(calculate);
    form.addEventListener('input',()=>{preview.hidden=true;});
  }
  function payload() {
    const data=Object.fromEntries(Object.entries(controls).map(([key,input])=>[key,input.value]));
    ['assignedTo','userId','progress'].forEach(key=>{if(key in data)data[key]=Number(data[key]);});
    return data;
  }
  const actions=hrNode('div',null,'hr-actions'),save=profileAction(section==='leaves'&&!row?'Submit request':'Save',()=>{},'primary');save.type='submit';
  actions.append(profileAction('Cancel',()=>dialog.close()),save);form.append(error,actions);
  form.onsubmit=async(event)=>{event.preventDefault();if(!form.reportValidity())return;save.disabled=true;error.textContent='';try{await hrApi(section+(row?'/'+row.id:''),row?'PUT':'POST',payload());dialog.close();await openHr(section);}catch(e){error.textContent=e.message;}finally{save.disabled=false;}};
  enhanceDocumentForm(dialog);dialog.showModal();
}
async function hrEmail(row) {
  const dialog=hrDialog('Email salary statement'),content=hrNode('div',null,'hr-form');
  const error=hrNode('p','','hr-error');error.setAttribute('role','alert');
  const close=profileAction('Close',()=>dialog.close());
  content.append(hrNode('h2','Email salary statement'),hrNode('p','Loading preview…','hr-subtitle'),error,close);dialog.append(content);enhanceDocumentForm(dialog);dialog.showModal();
  try {
    const preview=await hrApi(`payroll/${row.id}/email`);
    const body=hrNode('iframe');body.title='Salary statement report';body.setAttribute('sandbox','');body.srcdoc=preview.html;body.style.cssText='width:100%;height:720px;border:1px solid #dbe2ea;border-radius:8px;background:white';dialog.style.width='min(920px,96vw)';
    const send=profileAction('Send to employee',async()=>{
      send.disabled=true;error.textContent='';
      try {
        const result=await hrApi(`payroll/${row.id}/email`,'POST',{recipient:preview.recipient,version:preview.version});
        content.replaceChildren(hrNode('h2','Email submitted'),hrNode('p',result.message,'hr-formula'),close);
        await openHr('payroll');
      }catch(e){error.textContent=e.message;send.disabled=false;}
    },'primary');
    send.disabled=!preview.configured;
    content.replaceChildren(hrNode('h2','Email salary statement'),profileSection('Recipient and subject',[
      ['From',preview.sender],['To',preview.recipient],['Subject',preview.subject]
    ]),body);
    if(!preview.configured)content.append(hrNode('p',preview.configurationMessage || 'Email sending is not configured. Ask your administrator to configure the SMTP sender.','hr-error'));
    const actions=hrNode('div',null,'hr-actions');actions.append(close,send);content.append(error,actions);
  }catch(e){error.textContent=e.message;}
}






