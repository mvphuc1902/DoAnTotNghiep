function reportDateColumn(table,columns) {
  const preferred={sales_headers:'order_date',sales_orders:'order_date',sales_invoices:'posting_date',sales_returns:'return_date',purchase_headers:'order_date',purchase_receipts:'receipt_date',purchase_returns:'return_date',inventory_ledger:'posting_date',general_ledger:'posting_date',payments:'payment_date'}[table];
  return columns.includes(preferred)?preferred:['posting_date','document_date','order_date','created_at'].find(c=>columns.includes(c));
}
function openReportOptions(context) {
  const dialog=erpElement('dialog',null,'rounded-xl p-6 max-w-xl w-full'),form=erpElement('form',null,'space-y-4');
  form.append(erpElement('h2','Report · '+(tableLabels[context.table]||context.table),'text-xl font-bold'));
  const scope=erpElement('select');scope.setAttribute('aria-label','Phạm vi báo cáo');
  for(const [value,text] of [['range','Danh sách cùng loại trong khoảng ngày'],['current','Chứng từ / bản ghi đang mở']]){const o=erpElement('option',text);o.value=value;scope.append(o);}
  const from=erpElement('input'),to=erpElement('input');from.type=to.type='date';
  const dateRow=erpElement('div',null,'flex gap-3');
  [['From Date',from],['To Date',to]].forEach(([text,input])=>{const label=erpElement('label',text);input.setAttribute('aria-label',text);label.append(input);dateRow.append(label);});
  const error=erpElement('p','','text-rose-600');error.setAttribute('role','alert');
  scope.onchange=()=>{dateRow.hidden=scope.value==='current';};
  const print=erpButton('Xuất Report');print.type='submit';
  form.append(scope,dateRow,erpElement('p','Bỏ trống ngày để lấy toàn bộ. Từ ngày và đến ngày đều được tính trong báo cáo.','document-hint'),error,erpButton('Đóng',()=>dialog.close()),print);
  form.onsubmit=async e=>{
    e.preventDefault();error.textContent='';
    if(scope.value==='range'&&from.value&&to.value&&from.value>to.value){error.textContent='From Date phải nhỏ hơn hoặc bằng To Date.';return;}
    if(scope.value==='current'&&documentReportAdapters.has(context.table)){await documentReportAdapters.get(context.table)(context.table,context.row,print);return;}
    await exportRangeReport(context,scope.value==='current',from.value,to.value,print,error);
  };
  dialog.append(form);document.body.append(dialog);dialog.addEventListener('close',()=>dialog.remove());dialog.showModal();
}
async function exportRangeReport(context,current,from,to,button,error) {
  const popup=window.open('','_blank');if(!popup){error.textContent='Cho phép cửa sổ bật lên để mở Report.';return;}popup.opener=null;popup.document.body.textContent='Đang tải báo cáo…';button.disabled=true;
  const esc=v=>String(v??'—').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  try{
    let rows=[],columns=[];
    for(let page=0;;page++){
      const params=new URLSearchParams({page,size:100});
      if(current){params.set('filterColumn',context.table==='item_bom'?'parent_item_id':'id');params.set('filterValue',context.table==='item_bom'?context.row.parent_item_id:context.row.id);}
      else if(context.row.document_type&&['sales_headers','purchase_headers'].includes(context.table)){params.set('filterColumn','document_type');params.set('filterValue',context.row.document_type);}
      const result=await erpGet('/tables/'+context.table+'?'+params);columns=result.columns;rows.push(...result.rows);
      if(result.rows.length<100||rows.length>=Number(result.total))break;
    }
    const dateColumn=reportDateColumn(context.table,columns);
    if(!current&&(from||to)&&!dateColumn)throw Error('Danh mục này không có trường ngày để lọc. Bỏ trống From Date / To Date để xuất toàn bộ.');
    if(!current&&(from||to))rows=rows.filter(row=>{const date=String(row[dateColumn]||'').slice(0,10);return /^\d{4}-\d{2}-\d{2}$/.test(date)&&(!from||date>=from)&&(!to||date<=to);});
    columns=columns.filter(c=>!['password','password_hash'].includes(c));
    const title=tableLabels[context.table]||context.table;
    const html='<!doctype html><html lang="vi"><head><meta charset="utf-8"><title>'+esc(title)+'</title><style>'+documentReportStyles+' .sheet{width:fit-content;min-width:90%;max-width:none} @page{size:A4 landscape}</style></head><body><div class="toolbar"><button onclick="window.print()">In / Lưu PDF</button><button onclick="window.close()">Đóng</button></div><main class="sheet"><h1>'+esc(title)+'</h1><p>'+esc(current?'Bản ghi đang mở':(from||'Không giới hạn')+' → '+(to||'Không giới hạn'))+(dateColumn?' · '+esc(columnLabels[dateColumn]||dateColumn):'')+'</p><p>'+rows.length+' bản ghi</p><table><thead><tr>'+columns.map(c=>'<th>'+esc(columnLabels[c]||c)+'</th>').join('')+'</tr></thead><tbody>'+rows.map(row=>'<tr>'+columns.map(c=>'<td>'+esc(erpValue(row[c],c))+'</td>').join('')+'</tr>').join('')+'</tbody></table></main></body></html>';
    popup.document.open();popup.document.write(html);popup.document.close();
  }catch(e){error.textContent=e.message;popup.document.body.textContent='Không xuất được Report: '+e.message;}finally{button.disabled=false;}
}
