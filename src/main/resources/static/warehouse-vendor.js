async function showWarehouseVendorSummary(host,table,row){
 if(!['vendors','locations'].includes(table))return;
 const section=erpElement('section',null,'profile-section');section.append(erpElement('h3',table==='locations'?'Tồn kho và hàng chờ nhận':'Công nợ và chứng từ mua'));section.dataset.warehouseSummary='true';host.append(section);
 function grid(rows,fields){const wrap=erpElement('div',null,'overflow-auto'),t=erpElement('table',null,'min-w-full text-sm'),h=erpElement('tr');fields.forEach(([key,label])=>h.append(erpElement('th',label,'px-3 py-2')));const head=erpElement('thead');head.append(h);t.append(head);const body=erpElement('tbody');rows.forEach(r=>{const tr=erpElement('tr');fields.forEach(([key])=>tr.append(erpElement('td',String(r[key]??'—'),'px-3 py-2')));body.append(tr);});t.append(body);wrap.append(t);section.append(wrap);}
 try{
  const data=await erpGet('/master-summary/'+table+'/'+row.id);if(!section.isConnected)return;
  if(table==='locations'){
   section.append(erpElement('p','Tồn khả dụng = tồn thực tế − giữ chỗ − hàng ở QC/cách ly/Return/Bin khóa. Đang mua là lượng còn chờ nhận trên PO nháp/đã Release; chưa cộng vào tồn.','document-hint'));
   grid(data.stock,[['item_no','Mã Item'],['description','Tên Item'],['base_unit_of_measure','ĐVT'],['quantity','Tồn thực tế'],['reserved','Giữ chỗ'],['available','Khả dụng'],['incoming','Đang mua']]);
   if(!data.stock.length)section.append(erpElement('p','Kho chưa có tồn Item hoặc đơn mua chờ nhận.'));
   section.append(erpButton('Xem tồn kho',()=>openErpTable('inventory','location_id',row.id)),erpButton('Lịch sử nhập / xuất',()=>openErpTable('inventory_ledger','location_id',row.id)),erpButton('Phiếu nhập mua',()=>openErpTable('purchase_receipts','location_code',row.code)));
  }else{
   section.append(erpElement('p','Công nợ lấy từ hóa đơn đã Post, thanh toán, Credit Memo và hoàn tiền; tách riêng từng tiền tệ. Số âm là số dư trả trước/được hoàn.','document-hint'));
   grid(data.balances,[['currency_code','Tiền tệ'],['balance','Công nợ còn lại']]);
   if(!data.balances.length)section.append(erpElement('p','Chưa có phát sinh công nợ đã ghi sổ.'));
   grid(data.documents,[['document_type','Loại chứng từ'],['status','Trạng thái'],['currency_code','Tiền tệ'],['documents','Số chứng từ'],['total_amount','Tổng giá trị chứng từ']]);
   section.append(erpButton('Chứng từ mua',()=>openErpTable('purchase_headers','vendor_id',row.id)),erpButton('Phiếu nhập',()=>openErpTable('purchase_receipts','vendor_id',row.id)),erpButton('Vendor Ledger Entries',()=>openErpTable('purchase_vendor_entries','vendor_id',row.id)));
  }
 }catch(e){section.append(erpElement('p',e.message,'text-rose-600'));}
}
