const documentReportStyles = "\n        *{box-sizing:border-box}body{margin:0;background:#eef2f7;color:#17243a;font:13px/1.55 \"Segoe UI\",Arial,sans-serif}\n        .toolbar{position:sticky;top:0;display:flex;justify-content:flex-end;gap:10px;padding:14px max(24px,calc((100vw - 1040px)/2));background:#fff;border-bottom:1px solid #dfe6ef;z-index:2}\n        .toolbar button{border:0;border-radius:8px;padding:10px 16px;background:#1d4ed8;color:#fff;font-weight:700;cursor:pointer}\n        .toolbar button.secondary{background:#eef2ff;color:#30466d}.sheet{width:min(1040px,calc(100% - 40px));min-height:297mm;margin:28px auto;padding:22mm 20mm;background:#fff;box-shadow:0 18px 55px #23344d1a}\n        .top{display:flex;justify-content:space-between;gap:24px;padding-bottom:22px;border-bottom:3px solid #1d4ed8}\n        .brand{display:flex;gap:13px;align-items:center}.logo{display:grid;place-items:center;width:48px;height:48px;border-radius:14px;background:linear-gradient(135deg,#1d4ed8,#0f766e);color:white;font-size:22px;font-weight:800}\n        .eyebrow{margin:0;color:#64748b;font-size:10px;font-weight:800;letter-spacing:.16em;text-transform:uppercase}\n        h1{margin:3px 0 0;font-size:23px;line-height:1.2;letter-spacing:-.03em}.company{margin:5px 0 0;color:#64748b;font-size:11px}\n        .doc-meta{text-align:right;color:#64748b;font-size:11px}.doc-meta strong{display:block;color:#17243a;font-size:14px}\n        .title{display:flex;justify-content:space-between;align-items:end;gap:20px;padding:27px 0 20px}.title h2{margin:0;font-size:25px;letter-spacing:-.04em}.badge{padding:6px 11px;border-radius:99px;background:#eff6ff;color:#1d4ed8;font-size:10px;font-weight:800}\n        .addresses{display:grid;grid-template-columns:1fr 1fr;gap:14px}.address{padding:16px;border:1px solid #e5eaf1;border-radius:11px;background:#fbfcfe}.address h3,.facts h3{margin:0 0 8px;color:#64748b;font-size:10px;letter-spacing:.1em;text-transform:uppercase}\n        .address strong{display:block;margin-bottom:5px;font-size:14px}.address p{margin:2px 0;color:#526177;font-size:11px}\n        .facts{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;padding:17px 0 23px}.fact{padding:10px 12px;border-left:2px solid #dbeafe;background:#f8fafc}.fact span{display:block;color:#64748b;font-size:10px}.fact strong{display:block;margin-top:3px;font-size:11px;overflow-wrap:anywhere}\n        table{width:100%;border-collapse:collapse}thead{display:table-header-group}th{padding:10px 8px;background:#f1f5f9;color:#526177;text-align:left;font-size:9px;letter-spacing:.06em;text-transform:uppercase}\n        td{padding:11px 8px;border-bottom:1px solid #e7ecf2;vertical-align:top;font-size:10px}tbody tr:nth-child(even){background:#fbfcfe}.number{text-align:right;white-space:nowrap}.strong{font-weight:700}.item-no{font-weight:700;color:#1e3a8a}\n        .empty{text-align:center;padding:25px;color:#64748b}.summary-wrap{display:flex;justify-content:flex-end;margin-top:20px}.summary{width:min(360px,100%);padding:15px 17px;border:1px solid #e2e8f0;border-radius:11px}.sum-row{display:flex;justify-content:space-between;gap:16px;padding:5px 0;color:#526177;font-size:11px}.sum-row.total{margin-top:7px;padding-top:12px;border-top:1px solid #dbe3ed;color:#17243a;font-size:14px;font-weight:800}\n        .note{margin-top:22px;padding:13px 15px;border-radius:9px;background:#f8fafc;color:#526177;font-size:11px}.footer{display:flex;justify-content:space-between;gap:18px;margin-top:42px;padding-top:12px;border-top:1px solid #e5eaf1;color:#94a3b8;font-size:9px}\n        @page{size:A4 portrait;margin:12mm}@media print{body{background:#fff}.toolbar{display:none}.sheet{width:auto;min-height:0;margin:0;padding:0;box-shadow:none}.top,.title,.addresses,.facts,table,.summary-wrap,.note,.footer{break-inside:avoid}thead{display:table-header-group}tr{break-inside:avoid}}\n        @media(max-width:700px){.sheet{width:100%;margin:0;padding:22px 16px}.facts{grid-template-columns:1fr 1fr}.addresses{grid-template-columns:1fr}.top{align-items:flex-start}.title h2{font-size:21px}table{min-width:680px}.table-wrap{overflow:auto}.toolbar{padding:12px}}\n      ";
// Shared action entry point. New document modules register an adapter, not a new button.
const documentReportAdapters = new Map();
function registerDocumentReport(tables, adapter) {
  tables.forEach(table => documentReportAdapters.set(table, adapter));
}
function mountDocumentHeader(host, context) {
  if (!host || !documentReportAdapters.has(context.table)) return;
  host.querySelector('[data-document-report]')?.remove();
  const actions = erpElement('span'); actions.dataset.documentReport = 'true';
  const report = erpButton('Report', async event => {
    const adapter = documentReportAdapters.get(context.table);
    await adapter(context.table, context.row, event.currentTarget);
  });
  report.disabled = !context.row?.id;
  report.title = context.row?.id ? 'In / lưu PDF từ dữ liệu đã lưu của chứng từ' : 'Lưu chứng từ trước khi xuất Report';
  report.setAttribute('aria-label', 'Report · In / lưu PDF chứng từ đã lưu');
  actions.append(report); host.append(actions);
}
async function documentReportRows(table, column, value) {
  const rows = [];
  for (let page = 0; ; page++) {
    const data = await erpGet('/tables/' + table + '?' + new URLSearchParams({page:String(page),size:'100',filterColumn:column,filterValue:String(value)}));
    rows.push(...data.rows);
    if (data.rows.length < 100 || (Number.isFinite(data.total) && rows.length >= data.total)) return rows;
  }
}
registerDocumentReport(['sales_headers','sales_orders','sales_invoices','sales_returns'], exportSalesReport);
registerDocumentReport(['purchase_headers','purchase_receipts','purchase_returns'], exportPurchaseReport);

async function exportPurchaseReport(table, row, button) {
  const popup = window.open('', '_blank');
  if (!popup) { notifyCustomer('Cho phép cửa sổ bật lên để mở Report.'); return; }
  popup.opener = null;
  popup.document.body.textContent = 'Đang tải chứng từ đã lưu…';
  button.disabled = true;
  const escape = value => String(value ?? '').replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
  const display = (value, key) => escape(value == null ? '—' : erpValue(value,key));
  try {
    const saved = (await documentReportRows(table,'id',row.id))[0];
    if (!saved) throw new Error('Chứng từ không còn tồn tại.');
    const isHeader = table === 'purchase_headers';
    const lines = isHeader ? await documentReportRows('purchase_lines','purchase_header_id',row.id)
      : table === 'purchase_receipts' ? await documentReportRows('purchase_receipt_lines','receipt_id',row.id) : [];
    const vendor = saved.vendor_id ? (await documentReportRows('vendors','id',saved.vendor_id))[0] : null;
    const title = isHeader ? ({ORDER:'Purchase Order',QUOTE:'Purchase Quote',INVOICE:'Purchase Invoice',RETURN_ORDER:'Purchase Return Order'}[saved.document_type] || 'Purchase Document') : tableLabels[table];
    const number = saved.document_no || saved.receipt_no || saved.return_no;
    const minor = value => {
      const match = String(value ?? '0').match(/^(-?)(\d+)(?:\.(\d+))?$/);
      if (!match) return 0n;
      const n = BigInt(match[2])*100n + BigInt(((match[3]||'')+'00').slice(0,2));
      return match[1] ? -n : n;
    };
    const money = n => escape(erpDecimal(`${n<0n?'-':''}${(n<0n?-n:n)/100n}.${String((n<0n?-n:n)%100n).padStart(2,'0')}`,true));
    const total = minor(saved.total_amount), tax = minor(saved.vat_amount ?? saved.tax_amount);
    const facts = [['Vendor No.',vendor?.vendor_no],['Document Date',saved.order_date||saved.receipt_date||saved.return_date],['Posting Date',saved.posting_date||saved.receipt_date],['Vendor Invoice No.',saved.external_document_no],['Currency Code',saved.currency_code||'VND'],['Payment Terms',saved.payment_terms_code],['Requested Receipt Date',saved.requested_receipt_date],['Location Code',saved.location_code]];
    const tableColumns = isHeader ? ['item_no','description','location_code','unit_of_measure','quantity','unit_price','discount_percent','vat_percent','line_amount'] : ['item_no','description','location_code','unit_of_measure','quantity'];
    if (table === 'purchase_receipts')
      lines.forEach(line => { line.description = line.item_description || line.description; });
    const labels={item_no:'Mã Item',description:'Mô tả',location_code:'Kho',unit_of_measure:'ĐVT',quantity:'Số lượng',unit_price:'Đơn giá',discount_percent:'CK %',vat_percent:'VAT %',line_amount:'Thành tiền'};
    const html = `<!doctype html><html lang="vi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${escape(title)} ${escape(number)}</title><style>${documentReportStyles}</style></head><body>
      <div class="toolbar"><button class="secondary" onclick="window.close()">Đóng</button><button onclick="window.print()">In / Lưu PDF</button></div>
      <main class="sheet"><header class="top"><div class="brand"><div class="logo">E</div><div><p class="eyebrow">ERP Enterprise · Purchasing</p><h1>Chứng từ mua hàng</h1><p class="company">Báo cáo giao dịch nhà cung cấp</p></div></div><div class="doc-meta"><strong>${escape(number)}</strong>${escape(title)}<br>Ngày xuất: ${escape(new Date().toLocaleDateString('vi-VN'))}</div></header>
      <section class="title"><h2>${escape(title)}</h2><span class="badge">${escape(saved.status||(table==='purchase_receipts'?'POSTED':'—'))}</span></section>
      <section class="addresses"><article class="address"><h3>Nhà cung cấp · Buy-from</h3><strong>${escape(vendor?.name||saved.vendor_name||'—')}</strong>${[vendor?.address,vendor?.city,vendor?.phone,vendor?.email].filter(Boolean).map(v=>`<p>${escape(v)}</p>`).join('')}</article><article class="address"><h3>Nhận hàng · Receive-to</h3><strong>${escape(saved.location_code||'Theo từng dòng hàng')}</strong><p>${escape(vendor?.tax_no?'Mã số thuế NCC: '+vendor.tax_no:'')}</p><p>Tham chiếu: ${escape(saved.vendor_reference||'—')}</p></article></section>
      <section class="facts">${facts.filter(([label,value])=>value && value!=='—' && (label!=='Vendor Invoice No.' || saved.document_type==='INVOICE')).map(([label,value])=>`<div class="fact"><span>${escape(label)}</span><strong>${escape(value||'—')}</strong></div>`).join('')}</section>
      <div class="table-wrap"><table><thead><tr><th>Dòng</th>${tableColumns.map(c=>`<th${/quantity|price|percent|amount/.test(c)?' class="number"':''}>${escape(labels[c])}</th>`).join('')}</tr></thead><tbody>${lines.map((l,i)=>`<tr><td class="number">${i+1}</td>${tableColumns.map(c=>`<td${/quantity|price|percent|amount/.test(c)?' class="number"':''}>${display(l[c],c)}</td>`).join('')}</tr>`).join('')||`<tr><td colspan="${tableColumns.length+1}" class="empty">Chứng từ chưa có dòng hàng liên kết.</td></tr>`}</tbody></table></div>
      ${saved.total_amount!=null?`<section class="summary-wrap"><div class="summary"><div class="sum-row"><span>Tiền hàng sau chiết khấu</span><strong>${money(total-tax)}</strong></div><div class="sum-row"><span>Chiết khấu</span><strong>${money(minor(saved.discount_amount))}</strong></div><div class="sum-row"><span>Thuế VAT</span><strong>${money(tax)}</strong></div><div class="sum-row total"><span>Tổng thanh toán · ${escape(saved.currency_code||'VND')}</span><span>${money(total)}</span></div></div></section>`:''}
      ${saved.notes?`<aside class="note"><strong>Ghi chú</strong><br>${escape(saved.notes).replace(/\n/g,'<br>')}</aside>`:''}
      <footer class="footer"><span>${escape(title)} · ${escape(number)}</span><span>Được tạo từ ERP Enterprise</span></footer></main></body></html>`;

    popup.document.open(); popup.document.write(html); popup.document.close(); popup.focus();
  } catch (error) { popup.document.body.textContent = 'Không tạo được Report: ' + error.message; }
  finally { button.disabled = false; }
}
