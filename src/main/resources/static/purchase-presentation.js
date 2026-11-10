// Purchase uses the same FastTab and field styles as Sales, with purchasing captions.
function arrangePurchaseForm(form, row, type) {
  const content = form.querySelector('.document-content');
  if (!content) return;
  const names = {ORDER:'Purchase Order',QUOTE:'Purchase Quote',INVOICE:'Purchase Invoice',RETURN_ORDER:'Purchase Return Order'};
  const title = names[row?.document_type || type] || 'Purchase Document';
  form.classList.add('bc-sales-form', 'bc-purchase-form');
  form.querySelector('.document-breadcrumb').textContent = title;
  form.querySelector('.document-heading h2').textContent = row?.document_no ? `${row.document_no} · ${row.vendor_name || title}` : title;
  const captions = {document_no:'No.',document_type:'Document Type',vendor_id:'Buy-from Vendor No.',order_date:'Document Date',posting_date:'Posting Date',requested_receipt_date:'Requested Receipt Date',promised_receipt_date:'Promised Receipt Date',vendor_reference:'Vendor Reference',external_document_no:'Vendor Invoice No.',currency_code:'Currency Code',payment_terms_code:'Payment Terms Code',shipment_method_code:'Shipment Method Code',salesperson_code:'Purchaser Code',location_code:'Location Code',notes:'Notes',status:'Status',discount_percent:'Invoice Discount %',discount_amount:'Discount Amount',vat_amount:'VAT Amount',total_amount:'Total Incl. VAT'};
  for (const input of form.querySelectorAll('[name]')) {
    const caption = input.closest('label')?.querySelector('span');
    if (caption && captions[input.name]) caption.textContent = captions[input.name] + (input.required?' *':'');
  }
  const sections = [...content.querySelectorAll(':scope > .document-section')];
  const sectionName = section => section.querySelector('summary')?.firstChild?.textContent;
  for (const name of ['General','Purchase Lines','Lines & Totals','Invoice Details','System Information']) {
    const section = sections.find(s => sectionName(s) === name);
    if (section) content.append(section);
  }
  const documentType = row?.document_type || type;
  const primary = new Set(['vendor_id','order_date','location_code']);
  if (documentType === 'INVOICE') ['external_document_no','posting_date','currency_code','payment_terms_code'].forEach(n=>primary.add(n));
  if (documentType === 'ORDER') primary.add('requested_receipt_date');
  if (documentType === 'RETURN_ORDER') primary.add('notes');
  const internal = new Set(['id','document_type','document_no','status','created_at','updated_at','source_quote_id']);
  const irrelevant = new Set(documentType === 'INVOICE' ? [] : ['external_document_no']);
  if (!['QUOTE','ORDER'].includes(documentType)) ['requested_receipt_date','promised_receipt_date'].forEach(n=>irrelevant.add(n));
  const captionsVi = {vendor_id:'Nhà cung cấp',order_date:'Ngày chứng từ',location_code:'Kho mặc định cho dòng hàng',posting_date:'Ngày hạch toán',external_document_no:'Số hóa đơn nhà cung cấp',requested_receipt_date:'Ngày dự kiến nhận hàng',currency_code:'Tiền tệ',payment_terms_code:'Điều khoản thanh toán',notes:documentType==='RETURN_ORDER'?'Lý do trả hàng / ghi chú':'Ghi chú',salesperson_code:'Nhân viên phụ trách'};
  const allSections = [...content.querySelectorAll('.document-section')].filter(s=>s.querySelector(':scope > .document-fields'));
  const labels = allSections.flatMap(s=>[...s.querySelector(':scope > .document-fields').children]);
  const general = allSections.find(s=>s.querySelector('summary')?.firstChild?.textContent==='General');
  if (general) {
    const fields = general.querySelector(':scope > .document-fields');
    const optional = [];
    for (const label of labels) {
      const input = label.querySelector('[name]'); if (!input) continue;
      const name = input.name;
      const caption = label.querySelector('span');
      if(caption && captionsVi[name])caption.textContent=captionsVi[name]+(input.required?' *':'');
      label.classList.remove('document-field-collapsed');
      fields.append(label);
      if (internal.has(name) || (irrelevant.has(name) && !row?.[name])) {
        label.style.setProperty('display','none','important'); continue;
      }
      if (!primary.has(name) && !input.required) optional.push(label);
    }
    // Keep the controls and their values; only change which fields are initially visible.
    const summary = general.querySelector('summary');
    summary.querySelector('.document-fields-toggle')?.remove();
    summary.firstChild.textContent = 'Thông tin chứng từ';
    const toggle = erpElement('button', '', 'document-fields-toggle'); toggle.type='button';
    const expand = expanded => {
      optional.forEach(label=>label.classList.toggle('document-field-collapsed',!expanded));
      toggle.textContent=expanded?'Show less':'Show more';toggle.setAttribute('aria-expanded',String(expanded));general.open=true;
    };
    toggle.onclick=event=>{event.preventDefault();event.stopPropagation();expand(toggle.getAttribute('aria-expanded')!=='true');};
    general.documentExpandFields=()=>expand(true);
    if(optional.length)summary.append(toggle);expand(false);
    allSections.filter(section=>section!==general).forEach(section=>section.remove());
    const order = ['vendor_id','order_date','external_document_no','posting_date','location_code','requested_receipt_date','currency_code','payment_terms_code','notes'];
    order.forEach(name=>{const label=labels.find(l=>l.querySelector('[name]')?.name===name);if(label && primary.has(name))fields.append(label);});
    const hints = form.querySelector('.document-section-links');
    if(hints){hints.replaceChildren();for(const section of content.querySelectorAll('.document-section')){const text=section.querySelector('summary')?.firstChild?.textContent;if(!text)continue;hints.append(erpButton(text,()=>{section.open=true;section.scrollIntoView({behavior:'smooth',block:'start'});}));}}
  }
  form.documentRefresh?.();
}
