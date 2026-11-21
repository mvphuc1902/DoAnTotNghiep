function erpIsLineTable(table) { return /(?:^|_)lines$/.test(table) || table === 'sales_document_lines'; }
function erpListTarget(table,row) {
  if(table!=='sales_headers'||row.document_type==='QUOTE')return {table,id:row.id};
  const target={ORDER:['sales_orders','sales_order_id'],RETURN_ORDER:['sales_returns','sales_return_id'],INVOICE:['sales_invoices','sales_invoice_id']}[row.document_type];
  return target?{table:target[0],id:row[target[1]]}:{table,id:row.id};
}
function erpListLocked(table,row) {
  if(['purchase_headers','sales_returns'].includes(table))return row.status!=='DRAFT';
  if(table==='production_orders')return row.status!=='DRAFT';
  if(table==='sales_headers'&&row.document_type==='QUOTE')return row.status!=='DRAFT'||!!row.sales_order_id;
  return erpReadOnlyTables.has(table)&&!['sales_headers','sales_orders'].includes(table);
}
async function erpDeleteListRecord(table,row) {
  if(table==='item_bom') {
    const response=await fetch(erpApi+'/bom/'+encodeURIComponent(row.parent_item_id),{method:'DELETE'});
    if(!response.ok){const result=await response.json().catch(()=>({}));throw Error(result.message||'Không xóa được BOM.');}
    return;
  }
  const target=erpListTarget(table,row);
  if(target.id==null)throw Error('Không tìm thấy chứng từ nguồn.');
  await erpWrite(target.table,target.id,'DELETE',undefined,table==='inventory');
}
function mountErpListActions(toolbar,heading,state,data) {
  const lineTable=erpIsLineTable(state.table),selected=new Map(),checks=[];
  let busy=false;
  const th=erpElement('th','Thao tác','px-4 py-3 text-center');heading.append(th);
  const all=erpElement('input');all.type='checkbox';all.setAttribute('aria-label','Chọn tất cả dòng trên trang');
  const remove=erpButton('Xóa đã chọn (0)',()=>erase([...selected.values()]));
  function sync(){remove.textContent='Xóa đã chọn ('+selected.size+')';remove.disabled=busy||!canDeleteRecords()||!selected.size||[...selected.values()].some(r=>erpListLocked(state.table,r));all.checked=checks.length>0&&checks.every(c=>c.input.checked);all.indeterminate=checks.some(c=>c.input.checked)&&!all.checked;all.disabled=busy||!checks.length;}
  async function erase(rows){
    if(busy||!rows.length||!canDeleteRecords())return;
    if(!await confirmAction('Xóa '+rows.length+' bản ghi đã chọn? Dữ liệu liên quan và trạng thái chứng từ sẽ được kiểm tra trước khi xóa.'))return;
    busy=true;sync();let count=0,failure='';
    try{for(const row of rows){await erpDeleteListRecord(state.table,row);count++;}}catch(e){failure=e.message;}
    finally{busy=false;erpCatalog=null;await renderErpData();notifyCustomer('Đã xóa '+count+'/'+rows.length+' bản ghi.'+(failure?' '+failure:''));}
  }
  if(!lineTable){th.prepend(all);toolbar.append(remove);all.onchange=()=>{checks.forEach(({input,row})=>{input.checked=all.checked;if(all.checked)selected.set(String(row.id),row);else selected.delete(String(row.id));});sync();};}
  return (tr,row)=>{
    const td=erpElement('td',null,'px-3 py-2 text-center whitespace-nowrap');tr.append(td);
    const view=()=>state.table==='item_bom'?openBomDocument(row):showErpDetail(state.table,row,data.columns);
    if(lineTable){td.append(erpButton('Xem',e=>{e.stopPropagation();view();}));return;}
    const check=erpElement('input');check.type='checkbox';check.setAttribute('aria-label','Chọn '+(row.document_no||row.quote_no||row.parent_item_no||row.id));check.onclick=e=>e.stopPropagation();check.onchange=()=>{if(check.checked)selected.set(String(row.id),row);else selected.delete(String(row.id));sync();};checks.push({input:check,row});
    const edit=erpButton('Sửa',async e=>{e.stopPropagation();const button=e.currentTarget;button.disabled=true;try{const target=erpListTarget(state.table,row);if(target.id==null)throw Error('Không tìm thấy chứng từ nguồn.');const source=target.table===state.table?row:(await erpGet('/tables/'+target.table+'?'+new URLSearchParams({size:1,filterColumn:'id',filterValue:target.id}))).rows[0];if(!source)throw Error('Chứng từ nguồn không còn tồn tại.');await openErpForm(target.table,source);}catch(error){notifyCustomer(error.message);}finally{button.disabled=erpListLocked(state.table,row);}});
    const del=erpButton('Xóa',e=>{e.stopPropagation();erase([row]);});
    edit.disabled=erpListLocked(state.table,row);del.disabled=edit.disabled||!canDeleteRecords();
    if(edit.disabled)edit.title=del.title='Chứng từ đã khóa/ghi sổ hoặc dữ liệu hệ thống chỉ được xem.';
    td.append(check,edit,del,erpButton('Xem',e=>{e.stopPropagation();view();}));sync();
  };
}
