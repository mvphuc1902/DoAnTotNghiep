function customerListSelection() {
  const panel=document.getElementById('tab-customers');
  panel.querySelector('[data-customer-selection]')?.remove();
  const bar=erpElement('div',null,'flex gap-3 p-3');bar.dataset.customerSelection='true';
  const all=erpElement('input');all.type='checkbox';all.setAttribute('aria-label','Chọn tất cả khách hàng');
  const selected=new Map(),checks=[];
  const remove=erpButton('Xóa đã chọn (0)',async()=>{
    if(!selected.size||!canDeleteRecords())return;
    if(!await confirmAction('Xóa '+selected.size+' khách hàng đã chọn?'))return;
    remove.disabled=true;let done=0,failure='';
    try{for(const row of selected.values()){await customerRequest('/'+row.id,{method:'DELETE'});done++;}}catch(e){failure=e.message;}
    await loadCustomersFromDatabase();notifyCustomer('Đã xóa '+done+' khách hàng.'+(failure?' '+failure:''));
  });
  function sync(){remove.textContent='Xóa đã chọn ('+selected.size+')';remove.disabled=!selected.size||!canDeleteRecords();all.checked=checks.length>0&&checks.every(x=>x.input.checked);all.indeterminate=checks.some(x=>x.input.checked)&&!all.checked;}
  all.onchange=()=>{checks.forEach(({input,row})=>{input.checked=all.checked;if(input.checked)selected.set(row.id,row);else selected.delete(row.id);});sync();};
  bar.append(all,erpElement('span','Chọn tất cả'),remove);panel.querySelector('table').parentElement.before(bar);sync();
  return (cell,row)=>{const input=erpElement('input');input.type='checkbox';input.setAttribute('aria-label','Chọn '+row.customerNo);input.onclick=e=>e.stopPropagation();input.onchange=()=>{if(input.checked)selected.set(row.id,row);else selected.delete(row.id);sync();};checks.push({input,row});cell.prepend(input);};
}
