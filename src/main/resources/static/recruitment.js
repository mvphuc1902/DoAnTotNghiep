function openRecruitment(postId=null) {
  return openHrFeed(['string','number'].includes(typeof postId)?{kind:'RECRUITMENT',id:String(postId)}:null);
}
function recruitmentForm(row=null) {
  const dialog=hrDialog(row?'Edit job post':'New job post'),form=hrNode('form',null,'hr-form'),grid=hrNode('div',null,'hr-fields');
  form.append(hrNode('h2',row?'Edit job post':'New job post'),grid);dialog.append(form);
  const values={title:row?.title,department:row?.department,location:row?.location,employmentType:row?.employment_type||'FULL_TIME',deadline:hrDate(row?.deadline),contactEmail:row?.contact_email,description:row?.description,requirements:row?.requirements,status:row?.status||'DRAFT'};
  const controls={};
  [['title','Job title','text',200],['department','Department','text',255],['location','Location','text',255],['employmentType','Employment type',['FULL_TIME','PART_TIME','CONTRACT','INTERNSHIP']],['deadline','Application deadline','date'],['contactEmail','Application email','email',255],['description','Job description','textarea',8000],['requirements','Requirements','textarea',8000],['status','Publication status',['DRAFT','PUBLISHED','CLOSED']]].forEach(([name,label,type,max])=>{
    const wrapper=hrNode('label',null,'hr-field'+(type==='textarea'?' wide':'')),input=hrNode(Array.isArray(type)?'select':type==='textarea'?'textarea':'input',null,'hr-input');
    if(Array.isArray(type))type.forEach(v=>{const option=hrNode('option',v.replaceAll('_',' '));option.value=v;input.append(option);});else if(type!=='textarea')input.type=type;
    input.name=name;input.required=true;if(max)input.maxLength=max;input.value=values[name]||'';controls[name]=input;wrapper.append(hrNode('span',label+' *'),input);grid.append(wrapper);
  });
  const error=hrNode('p','','hr-error');error.setAttribute('role','alert');
  const imagePicker=hrImagePicker(dialog,row,'recruitment',error);grid.before(imagePicker.element);
  let saving=false;
  dialog.addEventListener('cancel',event=>{if(saving)event.preventDefault();});
  const actions=hrNode('div',null,'hr-actions'),save=profileAction('Save post',()=>{},'primary');save.type='submit';actions.append(profileAction('Cancel',()=>dialog.close()),save);form.append(error,actions);
  form.onsubmit=async e=>{
    e.preventDefault();if(saving)return;
    const payload=new FormData();payload.append('post',new Blob([JSON.stringify(Object.fromEntries(Object.entries(controls).map(([k,v])=>[k,v.value])))],{type:'application/json'}));
    imagePicker.appendTo(payload);

    saving=true;error.textContent='';save.textContent='Đang lưu…';form.querySelectorAll('input,select,textarea,button').forEach(control=>control.disabled=true);
    try{await hrApi('recruitment'+(row?'/'+row.id:''),row?'PUT':'POST',payload);dialog.close();await openRecruitment();}
    catch(errorValue){error.textContent=errorValue.message;}
    finally{saving=false;save.textContent='Save post';form.querySelectorAll('input,select,textarea,button').forEach(control=>control.disabled=false);}
  };
  enhanceDocumentForm(dialog);dialog.showModal();
}
