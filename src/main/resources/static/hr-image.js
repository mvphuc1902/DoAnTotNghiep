function hrImagePicker(dialog,row,path,error) {
  const element=hrNode('section',null,'hr-image-picker');element.setAttribute('aria-label','Thêm hình ảnh');
  const label=hrNode('label',null,'hr-field'),input=hrNode('input',null,'hr-input');input.type='file';input.accept='image/jpeg,image/png';
  label.append(hrNode('strong','Thêm hình ảnh'),input);
  const preview=hrNode('img',null,'recruitment-image recruitment-preview');preview.alt='Xem trước hình ảnh bài viết';
  const status=hrNode('p','','hr-subtitle');status.setAttribute('aria-live','polite');
  let selected=null,removed=false,url=null;
  const existing=row?.has_image?'/api/hr/'+path+'/'+encodeURIComponent(row.id)+'/image':null;
  function refresh(){
    if(url){URL.revokeObjectURL(url);url=null;}
    if(selected){url=URL.createObjectURL(selected);preview.src=url;preview.hidden=false;status.textContent=selected.name;}
    else if(existing&&!removed){preview.src=existing;preview.hidden=false;status.textContent='Ảnh đang dùng';}
    else{preview.removeAttribute('src');preview.hidden=true;status.textContent=removed?'Ảnh sẽ được gỡ khi lưu.':'Chọn ảnh từ máy tính để đăng kèm bài viết.';}
    remove.hidden=preview.hidden;
  }
  const remove=profileAction('Gỡ ảnh',()=>{selected=null;removed=Boolean(existing);input.value='';refresh();});
  input.onchange=()=>{
    error.textContent='';const file=input.files[0];if(!file)return;
    if(!['image/jpeg','image/png'].includes(file.type)||!file.size||file.size>5*1024*1024){
      error.textContent='Chọn ảnh JPG/PNG hợp lệ, tối đa 5 MB.';input.value='';selected=null;refresh();return;
    }
    selected=file;removed=false;refresh();
  };
  element.append(label,hrNode('small','Không bắt buộc · JPG/PNG · tối đa 5 MB, 16 triệu điểm ảnh','hr-subtitle'),preview,status,remove);
  dialog.addEventListener('close',()=>{if(url)URL.revokeObjectURL(url);},{once:true});refresh();
  return {element,previewSource:()=>preview.hidden?null:preview.src,appendTo(payload){if(selected)payload.append('image',selected);if(removed)payload.append('removeImage','true');}};
}
