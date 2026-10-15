async function hrWorkspace(section,path,title,render) {
  hrState.section=section;const version=++hrState.version;switchTab('hr');
  document.getElementById('nav-hr-'+section)?.classList.add('active');
  const root=document.getElementById('tab-hr');root.replaceChildren(hrNode('p','Loading…'));
  try{const rows=await hrApi(path);if(version!==hrState.version)return;root.replaceChildren(hrNode('h2',title,'text-xl font-bold'));render(root,rows);}
  catch(e){root.replaceChildren(hrNode('p',e.message,'hr-error'),profileAction('Retry',()=>hrWorkspace(section,path,title,render)));}
}
function openHrEmployees() {
  return hrWorkspace('employees','employees','Employees',(root,rows)=>{
    root.append(hrNode('p','Employee profiles · up to 500 most recent records','hr-subtitle'));
    if(hrPayrollManager())root.append(profileAction('+ Thêm nhân viên',()=>hrEmployeeForm(),'primary'));
    const search=hrNode('input',null,'hr-input');search.type='search';search.placeholder='Search employee, department or email…';search.setAttribute('aria-label','Search employees');search.style.margin='16px 0';
    const list=hrNode('div');root.append(search,list);
    function fill(){list.replaceChildren();const matches=rows.filter(r=>Object.values(r).join(' ').toLowerCase().includes(search.value.toLowerCase()));
      matches.forEach(r=>{
        const card=hrNode('article',null,'profile-card');card.style.marginBottom='12px';
        card.append(profileHeader('EMPLOYEE',r.name,r.employee_code,r.active?'Active':'Inactive'),profileSection('Employee profile',[
          ['Department',r.department],['Position',r.position],['Team',r.team],['Email',r.email],['Phone',r.phone],['Hire date',hrDate(r.hire_date)],['Linked account',r.username||'Not linked — ask Admin to link a user account for payroll']
        ]));
        if(hrPayrollManager()){
          const actions=hrNode('div',null,'profile-actions');
          const remove=profileAction('Xóa nhân viên',async()=>{
            if(!await confirmAction('Xóa hồ sơ nhân viên '+r.name+'?'))return;
            remove.disabled=true;
            try{await hrApi('employees/'+encodeURIComponent(r.id),'DELETE');await openHrEmployees();}
            catch(error){notifyCustomer(error.message);remove.disabled=false;}
          });remove.classList.add('hr-employee-delete');
          actions.append(profileAction('Sửa nhân viên',()=>hrEmployeeForm(r),'primary'),remove);card.append(actions);
        }
        list.append(card);
      });if(!matches.length)list.append(hrNode('p','No employees found.','hr-empty'));
    }search.oninput=fill;fill();
  });
}
function hrEmployeeForm(row=null) {
  const title=row?'Sửa nhân viên':'Thêm nhân viên',dialog=hrDialog(title),form=hrNode('form',null,'hr-form'),grid=hrNode('div',null,'hr-fields');
  const values={employeeCode:row?.employee_code,name:row?.name,email:row?.email,phone:row?.phone,department:row?.department,position:row?.position,hireDate:hrDate(row?.hire_date),active:String(row?.active??true)};
  const controls={};
  [['employeeCode','Mã nhân viên','text',50],['name','Họ và tên','text',255],['email','Email','email',255],['phone','Số điện thoại','tel',30],['department','Phòng ban','text',100],['position','Chức vụ','text',100],['hireDate','Ngày vào làm','date'],['active','Trạng thái','select']].forEach(([key,label,type,max])=>{
    const wrapper=hrNode('label',null,'hr-field'),input=hrNode(type==='select'?'select':'input',null,'hr-input');
    if(type==='select')[[true,'Đang làm việc'],[false,'Ngừng hoạt động']].forEach(([value,text])=>{const option=hrNode('option',text);option.value=String(value);input.append(option);});else input.type=type;
    input.name=key;input.required=['employeeCode','name','active'].includes(key);if(max)input.maxLength=max;input.value=values[key]??'';
    wrapper.append(hrNode('span',label+(input.required?' *':'')),input);grid.append(wrapper);controls[key]=input;
  });
  const error=hrNode('p','','hr-error');error.setAttribute('role','alert');const actions=hrNode('div',null,'hr-actions'),save=profileAction('Lưu nhân viên',()=>{},'primary');save.type='submit';
  let saving=false;dialog.addEventListener('cancel',e=>{if(saving)e.preventDefault();});
  actions.append(profileAction('Hủy',()=>dialog.close()),save);form.append(hrNode('h2',title),grid,error,actions);dialog.append(form);
  form.onsubmit=async e=>{
    e.preventDefault();if(saving)return;
    const payload=Object.fromEntries(Object.entries(controls).map(([key,input])=>[key,key==='active'?input.value==='true':input.value.trim()||null]));
    saving=true;error.textContent='';form.querySelectorAll('input,select,button').forEach(control=>control.disabled=true);
    try{await hrApi('employees'+(row?'/'+row.id:''),row?'PUT':'POST',payload);dialog.close();await openHrEmployees();}
    catch(failure){error.textContent=failure.message;}
    finally{saving=false;form.querySelectorAll('input,select,button').forEach(control=>control.disabled=false);}
  };enhanceDocumentForm(dialog);dialog.showModal();
}
function openHrPosts() { return openHrFeed(); }
function hrReadPost(row) {
  const dialog=hrDialog('Read post'),close=hrNode('div',null,'profile-close');close.append(profileAction('Close',()=>dialog.close()));
  const content=hrNode('div',row.content,'profile-section');content.style.cssText='white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.8';
  dialog.append(close,profileHeader(row.category==='NEWS'?'NEWS':'HR ANNOUNCEMENT',row.title,row.author_name||'Preview',row.status));
  const imageSource=row.previewImage||(row.has_image?'/api/hr/posts/'+encodeURIComponent(row.id)+'/image':null);
  if(imageSource){const image=hrNode('img',null,'recruitment-image');image.src=imageSource;image.alt='Ảnh bài viết: '+row.title;dialog.append(image);}
  dialog.append(profileSection('Summary',[['Overview',row.summary||'—']]),content);enhanceDocumentForm(dialog);dialog.showModal();
}
function hrPostForm(row=null) {
  const dialog=hrDialog(row?'Edit post':'New post'),form=hrNode('form',null,'hr-form'),grid=hrNode('div',null,'hr-fields'),controls={};
  form.append(hrNode('h2',row?'Edit post':'New post'),grid);dialog.append(form);
  [['title','Title','text',200],['category','Category',['NEWS','ANNOUNCEMENT']],['summary','Summary','textarea',500],['content','Content','textarea',20000],['status','Status',['DRAFT','PUBLISHED','ARCHIVED']]].forEach(([key,label,type,max])=>{
    const wrapper=hrNode('label',null,'hr-field'+(type==='textarea'?' wide':'')),input=hrNode(Array.isArray(type)?'select':type==='textarea'?'textarea':'input',null,'hr-input');
    if(Array.isArray(type))type.forEach(v=>{const o=hrNode('option',v);o.value=v;input.append(o);});else if(type!=='textarea')input.type=type;
    input.name=key;input.required=key!=='summary';if(max)input.maxLength=max;input.value=row?.[key]||({category:'NEWS',status:'DRAFT'}[key])||'';if(key==='content')input.style.minHeight='240px';controls[key]=input;wrapper.append(hrNode('span',label+(input.required?' *':'')),input);grid.append(wrapper);
  });
  const payload=()=>Object.fromEntries(Object.entries(controls).map(([k,v])=>[k,v.value]));
  const error=hrNode('p','','hr-error');error.setAttribute('role','alert');const actions=hrNode('div',null,'hr-actions'),save=profileAction('Save post',()=>{},'primary');save.type='submit';
  const imagePicker=hrImagePicker(dialog,row,'posts',error);grid.before(imagePicker.element);
  let saving=false;dialog.addEventListener('cancel',event=>{if(saving)event.preventDefault();});
  actions.append(profileAction('Cancel',()=>dialog.close()),profileAction('Preview',()=>{if(form.reportValidity())hrReadPost({...payload(),previewImage:imagePicker.previewSource()});}),save);form.append(error,actions);
  form.onsubmit=async e=>{
    e.preventDefault();if(saving)return;
    const data=new FormData();data.append('post',new Blob([JSON.stringify(payload())],{type:'application/json'}));imagePicker.appendTo(data);
    saving=true;error.textContent='';save.textContent='Đang lưu…';form.querySelectorAll('input,select,textarea,button').forEach(control=>control.disabled=true);
    try{await hrApi('posts'+(row?'/'+row.id:''),row?'PUT':'POST',data);dialog.close();await openHrPosts();}
    catch(e){error.textContent=e.message;}
    finally{saving=false;save.textContent='Save post';form.querySelectorAll('input,select,textarea,button').forEach(control=>control.disabled=false);}
  };enhanceDocumentForm(dialog);dialog.showModal();
}
