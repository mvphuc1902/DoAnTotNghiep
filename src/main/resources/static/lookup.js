// A single searchable control; retain the original select for form values and handlers.
function unifiedLookup(search, select) {
  if (!search || !select || select.dataset.lookupReady) return;
  select.dataset.lookupReady='true';
  const wrapper=document.createElement('div');wrapper.className='lookup-control';search.before(wrapper);wrapper.append(search,select);
  select.style.setProperty('display','none','important');select.tabIndex=-1;
  const list=document.createElement('div');list.className='lookup-options';list.hidden=true;list.setAttribute('role','listbox');list.id='lookup-'+(++unifiedLookup.sequence);wrapper.append(list);
  search.type='text';search.autocomplete='off';search.setAttribute('role','combobox');search.setAttribute('aria-autocomplete','list');search.setAttribute('aria-controls',list.id);search.setAttribute('aria-expanded','false');
  let expanded=false,index=-1,options=[];
  const caption=()=>select.value?select.selectedOptions[0]?.textContent||'':'';
  function sync(){search.disabled=select.disabled;search.required=select.required;search.setAttribute('aria-required',String(select.required));search.setCustomValidity(select.required&&!select.value?'Vui lòng chọn một bản ghi trong danh sách.':'');if(!expanded)search.value=caption();}
  function close(){expanded=false;list.hidden=true;search.setAttribute('aria-expanded','false');search.removeAttribute('aria-activedescendant');sync();}
  function choose(option){select.value=option.value;select.dispatchEvent(new Event('change',{bubbles:true}));close();search.focus();}
  function position(){
    const rect=search.getBoundingClientRect(),below=window.innerHeight-rect.bottom-12;
    list.style.left=rect.left+'px';list.style.width=rect.width+'px';list.style.maxHeight=Math.max(100,Math.min(280,below<150?rect.top-12:below))+'px';
    list.style.top=below<150?'auto':(rect.bottom+5)+'px';list.style.bottom=below<150?(window.innerHeight-rect.top+5)+'px':'auto';
  }
  function draw(){
    if(!expanded)return;
    position();
    const query=search.value.toLocaleLowerCase();options=[...select.options].filter(o=>!o.disabled&&(!query||o.textContent.toLocaleLowerCase().includes(query)));index=-1;list.replaceChildren();search.removeAttribute('aria-activedescendant');
    options.forEach((option,i)=>{const item=document.createElement('div');item.className='lookup-option';item.setAttribute('role','option');item.id=list.id+'-'+i;item.textContent=option.textContent;item.setAttribute('aria-selected',String(option.value===select.value));item.onmousedown=e=>e.preventDefault();item.onclick=()=>choose(option);list.append(item);});
    if(!options.length){const empty=document.createElement('div');empty.className='lookup-empty';empty.textContent='Không có lựa chọn phù hợp. Hãy thử từ khóa khác.';list.append(empty);}
  }
  const open=()=>{if(expanded||search.disabled)return;expanded=true;search.value='';list.hidden=false;search.setAttribute('aria-expanded','true');draw();};
  search.addEventListener('focus',open);
  search.addEventListener('click',open);
  search.addEventListener('input',()=>{select.value='';search.setCustomValidity(select.required?'Vui lòng chọn một bản ghi trong danh sách.':'');draw();});
  search.addEventListener('blur',close);
  search.addEventListener('keydown',event=>{
    if(event.key==='Escape'){event.preventDefault();event.stopPropagation();close();}
    if(['ArrowDown','ArrowUp'].includes(event.key)){event.preventDefault();open();if(!options.length)return;index=index<0?(event.key==='ArrowDown'?0:options.length-1):(index+(event.key==='ArrowDown'?1:-1)+options.length)%options.length;[...list.children].forEach((item,i)=>item.classList.toggle('highlighted',i===index));search.setAttribute('aria-activedescendant',list.children[index].id);list.children[index].scrollIntoView({block:'nearest'});}
    if(event.key==='Enter'&&expanded){event.preventDefault();if(index>=0)choose(options[index]);}
  });
  select.addEventListener('change',sync);
  const observer=new MutationObserver(changes=>{if(changes.some(change=>change.type==='childList')&&expanded&&search.value)select.value='';sync();if(select.disabled)close();else draw();});observer.observe(select,{childList:true,subtree:true,attributes:true,attributeFilter:['disabled','required']});
  select.closest('dialog')?.addEventListener('scroll',()=>{if(expanded)position();},true);
  select.closest('dialog')?.addEventListener('close',close);
  // Preserve original validity checks while focusing the visible control.
  select.addEventListener('invalid',event=>{event.preventDefault();search.focus();search.reportValidity();});
  sync();
}
unifiedLookup.sequence=0;
function unifyFormLookups(form){
  form.querySelectorAll('input[name="payment_method_code"],input[name="paymentMethodCode"],input[name="payment_method"],input[name="paymentMethod"]').forEach(input=>{
    if(input.paymentRefresh){input.paymentRefresh();return;}
    const select=document.createElement('select'),search=document.createElement('input'),message=document.createElement('small');
    select.required=input.required;search.type='search';search.placeholder='Chọn phương thức từ Payment…';search.setAttribute('aria-label','Phương thức thanh toán từ Payment');
    input.before(search,select,message);input.type='hidden';
    const apply=values=>{const current=input.value;select.replaceChildren();const empty=document.createElement('option');empty.value='';empty.textContent='Chưa chọn';select.append(empty);[...new Set([...values,...(current?[current]:[])])].forEach(value=>{const option=document.createElement('option');option.value=value;option.textContent=value;select.append(option);});select.value=current;};
    select.addEventListener('change',()=>{input.value=select.value;input.dispatchEvent(new Event('change',{bubbles:true}));});
    search.addEventListener('input',()=>{input.value='';});
    let request=0;
    input.paymentRefresh=async()=>{const version=++request;apply([]);message.textContent='Đang tải phương thức từ Payment…';try{const response=await fetch('/api/erp/payment-methods');if(!response.ok)throw new Error();const values=await response.json();if(version!==request)return;apply(values);message.textContent=values.length?'Nguồn: Payment · Phương thức thanh toán':'Chưa có phương thức thanh toán trong dữ liệu Payment.';}catch(error){if(version===request)message.textContent='Không tải được phương thức thanh toán. Đóng và mở lại form để thử lại.';}};
    unifiedLookup(search,select);input.paymentRefresh();
  });
  form.querySelectorAll('label').forEach(label=>{const select=label.querySelector('select');let search=label.querySelector('input[type=search]');if(select&&!select.dataset.lookupReady&&!search&&['userId','assignedTo'].includes(select.name)){search=document.createElement('input');search.type='search';search.placeholder='Tìm và chọn nhân viên…';search.setAttribute('aria-label','Tìm và chọn nhân viên');select.before(search);}if(select&&search)unifiedLookup(search,select);});
}
