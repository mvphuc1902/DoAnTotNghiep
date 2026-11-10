function salesDocumentLines(form, isQuote = false, locationControl = null) {
  const node = (tag, text, className = '') => erpElement(tag, text, className);
  const section = node('details', null, 'document-section sales-document'); section.open = true;
  section.append(node('summary', 'Sales Lines'));
  const tools = node('div', null, 'sales-line-tools');
  const category = node('select'); category.setAttribute('aria-label','Loại Item');
  [['','Tất cả loại Item'],['INVENTORY','Inventory · Hàng tồn kho'],['SERVICE','Service · Dịch vụ'],['NON_INVENTORY','Non-Inventory · Không quản lý tồn']].forEach(([value,label])=>{const option=node('option',label);option.value=value;category.append(option)});
  const search = node('input'); search.type = 'search'; search.placeholder = 'Tìm mã hoặc tên vật tư…'; search.setAttribute('aria-label', 'Tìm vật tư');
  const picker = node('select'); picker.setAttribute('aria-label', 'Chọn vật tư');
  const message = node('p', 'Đang tải vật tư…', 'document-hint'); message.setAttribute('role', 'status');
  const add = erpButton('+ Thêm dòng', () => {
    const item = items.find(value => String(value.id) === picker.value);
    if (item) { addLine(item); picker.value=''; picker.dispatchEvent(new Event('change',{bubbles:true})); }
  }); add.disabled = true;
  tools.append(category, search, picker, add);
  const scroll = node('div', null, 'sales-lines-scroll');
  const table = node('table'); const head = node('thead'); const headers = node('tr');
  ['Vật tư', 'Location Code', 'Đơn vị', 'Số lượng', 'Đơn giá (VND)', 'CK %', 'VAT %','Thành tiền chưa VAT' ,'Thành tiền (VAT)', ''].forEach(caption => headers.append(node('th', caption)));
  head.append(headers); const body = node('tbody'); table.append(head, body); scroll.append(table);
  const totals = node('dl', null, 'sales-totals');
  const amounts = {};
  [['net', 'Tổng trước VAT'], ['discount', 'Chiết khấu'], ['vat', 'Thuế VAT'], ['total', 'Tổng thanh toán (VND)']].forEach(([key, caption]) => {
    amounts[key] = node('dd', '0 ₫'); totals.append(node('dt', caption), amounts[key]);
  });
  const empty = node('p', 'Chưa có dòng vật tư. Chọn vật tư rồi bấm Thêm dòng.', 'document-hint');
  section.append(tools, message, scroll, empty, totals, node('p', isQuote ? 'Lưu báo giá chưa thay đổi tồn kho. Make Order sẽ tạo đơn và kiểm tra tồn kho.' : 'Lưu đơn sẽ trừ tồn kho. Nếu thiếu hàng, toàn bộ đơn sẽ không được lưu.', 'document-hint'));
  form.querySelector('.document-content').append(section); unifiedLookup(search, picker);
  const rows = []; let items = [], version = 0, timer;
  const selectedLocation = () => locationControl?.value
    ? locationControl.selectedOptions[0]?.textContent || '—' : '—';
  const fixed = (value, scale) => {
    if (!/^\d+(\.\d+)?$/.test(value)) return 0n;
    const [whole, part = ''] = value.split('.'); return BigInt(whole + part.padEnd(scale, '0').slice(0, scale));
  };
  const fixedPrice = value => {
    try { return fixed(erpNormalizePrice(value), 2); }
    catch { return 0n; }
  };
  const round = (value, divisor) => (value + divisor / 2n) / divisor;
  const money = cents => erpDecimal(`${cents / 100n}.${String(cents % 100n).padStart(2, '0')}`, true);
  function update() {
    let net = 0n, discount = 0n, vat = 0n;
    rows.forEach(row => {
      row.location.textContent = selectedLocation();
      const gross = round(fixed(row.quantity.value, 3) * fixedPrice(row.unitPrice.value), 1000n);
      const reduction = round(gross * fixed(row.discountPercent.value, 2), 10000n);
      const base = gross - reduction;
      const tax = round(base * fixed(row.vatPercent.value, 2), 10000n);
      net += base; discount += reduction; vat += tax;
      row.baseAmount.textContent = base >= 0n ? money(base) : '—';
      row.amount.textContent = base >= 0n ? money(base + tax) : '—';
    });
    amounts.net.textContent = money(net); amounts.discount.textContent = money(discount);
    amounts.vat.textContent = money(vat); amounts.total.textContent = money(net + vat);
    empty.hidden = rows.length > 0;
  }
  function addLine(item) {
    const row = {item: {id: String(item.id)}, description: item.name || item.description || item.item_no, unitOfMeasure: item.base_unit_of_measure || ''};
    const tr = node('tr'); tr.append(node('td', `${item.item_no || ''} · ${row.description || ''}`), node('td', row.unitOfMeasure));
    row.location = node('td', selectedLocation());
    tr.insertBefore(row.location, tr.children[1]);
    [['quantity', 'Số lượng', '1', '0.001', '', '0.001'], ['unitPrice', 'Đơn giá', item.unit_price || '0.00', '0', '', '0.01'], ['discountPercent', 'Chiết khấu', '0', '0', '100', '0.01'], ['vatPercent', 'VAT', '0', '0', '100', '0.01']].forEach(([key, label, value, min, max, step]) => {
      const td = node('td'), input = node('input'); input.type = 'number';
      if (key === 'unitPrice') erpConfigurePriceInput(input);
      input.value = key === 'unitPrice' ? erpPrice(value) : value;
      input.required = true; input.min = min; input.step = step; if (max) input.max = max;
      input.setAttribute('aria-label', `${label} — ${row.description}`); input.addEventListener('input', update);
      row[key] = input; td.append(input); tr.append(td);
    });
    row.baseAmount = node('td'); row.amount = node('td'); tr.append(row.baseAmount, row.amount);
    const removeCell = node('td'), remove = erpButton('Xóa', () => { rows.splice(rows.indexOf(row), 1); tr.remove(); update(); });
    remove.setAttribute('aria-label', `Xóa dòng ${row.description}`); removeCell.append(remove); tr.append(removeCell);
    rows.push(row); body.append(tr); update(); row.quantity.focus();
  }
  async function lookup() {
    const request = ++version; add.disabled = true; picker.disabled = true; message.textContent = 'Đang tải vật tư…';
    try {
      const query=new URLSearchParams({size:'100',search:search.value.trim()});
      if(category.value){query.set('filterColumn','item_category');query.set('filterValue',category.value)}
      const data = await erpGet('/tables/items?' + query);
      if (request !== version) return;
      items = data.rows.filter(item=>!item.blocked); picker.replaceChildren();
      const placeholder=node('option','Chọn Item từ danh sách…');placeholder.value='';picker.append(placeholder);
      items.forEach(item => { const option = node('option', `${item.item_no || ''} · ${item.name || item.description || ''}`); option.value = item.id; picker.append(option); });
      picker.value='';picker.disabled = false; add.disabled = true;
      message.textContent = items.length ? (data.total > 100 ? '100 kết quả đầu. Nhập từ khóa để tìm thêm.' : `${data.total} vật tư`) : 'Không có vật tư phù hợp.';
    } catch (error) { if (request === version) message.textContent = 'Không tải được vật tư. Nhập lại từ khóa để thử lại.'; }
  }
  search.addEventListener('input', () => { clearTimeout(timer); version++; add.disabled = true; timer = setTimeout(lookup, 250); }); lookup();
  picker.addEventListener('change',()=>{add.disabled=!items.some(item=>String(item.id)===picker.value)});
  category.addEventListener('change',()=>{clearTimeout(timer);search.value='';picker.value='';lookup()});
  locationControl?.addEventListener('change', update);
  return () => {
    if (!rows.length) { section.open = true; search.focus(); throw new Error('Hãy thêm ít nhất một dòng vật tư.'); }
    return rows.map(row => {
      let unitPrice;
      try { unitPrice = erpNormalizePrice(row.unitPrice.value); }
      catch { row.unitPrice.focus(); throw new Error(`Nhập đơn giá hợp lệ cho ${row.description}, ví dụ 1.500.000.`); }
      return {item: row.item, description: row.description, unitOfMeasure: row.unitOfMeasure, quantity: row.quantity.value,
        unitPrice, discountPercent: row.discountPercent.value, vatPercent: row.vatPercent.value};
    });
  };
}

async function saveSalesDocument(header, lines, isQuote = false) {
  const payload = {};
  Object.entries(header).forEach(([key, value]) => {
    if (key === 'quote_no') payload.orderNo = value;
    else if (key === 'customer_id' || key === 'location_id') payload[key.replace('_id', '')] = value ? {id: value} : null;
    else payload[key.replace(/_([a-z])/g, (_, letter) => letter.toUpperCase())] = value;
  });
  payload.documentType = isQuote ? (payload.documentType || 'QUOTE') : 'ORDER';
  payload.status = 'OPEN'; payload.currencyCode = payload.currencyCode || 'VND'; payload.lines = lines;
  let response;
  try {
    response = await fetch(erpApi + '/sales-quotes', {method: 'POST', headers: {'Content-Type': 'application/json', Accept: 'application/json'}, body: JSON.stringify(payload), signal: AbortSignal.timeout(20000)});
  } catch (error) { throw new Error('Chưa nhận được kết quả. Tải lại danh sách để kiểm tra trước khi gửi lại.'); }
  if (!response.ok) { const error = await response.json().catch(() => ({})); throw new Error(error.message || 'Không lưu được đơn. Kiểm tra tồn kho và thông tin đơn hàng.'); }
  return response.json();
}

async function makeSalesQuoteOrder(quote, button) {
  if (button.disabled) return;
  if (quote.document_type !== 'QUOTE') {
    notifyCustomer('Make Order chỉ áp dụng cho Sales Quotes.');
    return;
  }
  if (quote.sales_order_id) return openErpTable('sales_orders', 'id', quote.sales_order_id);
  const errorBox = document.getElementById('sales-quote-conversion-error');
  if (errorBox) errorBox.textContent = '';
  button.disabled = true;
  try {
    const response = await fetch(erpApi + '/sales-quotes/' + encodeURIComponent(quote.id) + '/make-order', {method: 'POST'});
    const result = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(result.message || 'Không tạo được đơn. Kiểm tra thông tin và tồn kho.');
    erpCatalog = null;
    notifyCustomer(result.documentNo
      ? `Đã tạo Sales Order ${result.documentNo} · Posting Date: ${result.postingDate}`
      : 'Đã mở Sales Order của báo giá.');
    await openErpTable('sales_orders', 'id', result.id);
  } catch (error) {
    if (errorBox) errorBox.textContent = error.message;
    notifyCustomer(error.message);
  }
  finally { button.disabled = false; }
}

async function showSalesDocumentLines(dialog, table, header) {
  const section = erpElement('section', null, 'profile-section sales-detail-lines sales-document');
  section.append(erpElement('h3', 'Sales Lines'));
  const status = erpElement('p', 'Đang tải dòng vật tư…', 'document-hint'); section.append(status); dialog.append(section);
  if (table === 'sales_headers' && header.document_type === 'QUOTE' && !header.sales_order_id) section.append(erpButton('Make Order', event => makeSalesQuoteOrder(header, event.currentTarget)));
  const wrapper = erpElement('div', null, 'sales-lines-scroll');
  const grid = erpElement('table');
  const head = erpElement('tr');
  ['Document Type', 'Document No.', 'Sell-to Customer No.', 'Sell-to Customer Name', 'No.', 'Description',
    'Location Code', 'Unit of Measure Code', 'Quantity', 'Unit Price (VND)', 'Line Discount %', 'VAT %',
    'Line Amount Excl. VAT', 'Line Amount Incl. VAT'].forEach(title => head.append(erpElement('th', title, 'p-2')));
  const thead = erpElement('thead', null, 'bg-slate-100'); thead.append(head); const body = erpElement('tbody'); grid.append(thead, body); wrapper.append(grid); section.append(wrapper);
  let page = 0;
  const more = erpButton('Tải thêm dòng', () => load(false)); more.hidden = true; section.append(more);
  async function load(reset = true) {
    if (reset) { body.replaceChildren(); page = 0; }
    more.disabled = true;
    try {
      const details = await erpGet('/sales-form/values?' + new URLSearchParams({
        table, id: String(header.id)
      }));
      const result = await erpGet('/tables/sales_document_lines?' + new URLSearchParams({
        size:'100',page:String(page),filterColumn:'document_id',
        filterValue:String(details._header_id || header.id)
      }));
      result.rows.forEach(line => {
        const row = erpElement('tr');
        ['document_type','document_no','customer_no','sell_to_name','item_no','description','location_code',
          'unit_of_measure','quantity','unit_price','discount_percent','vat_percent','line_amount_ex_vat',
          'line_amount'].forEach(key => row.append(erpElement('td', erpValue(line[key],key), 'p-2')));
        body.append(row);
      });
      page++;
      status.textContent = result.total ? `${result.total} dòng Sales Lines` : 'Chưa có dòng vật tư.';
      more.hidden = page * 100 >= result.total;
    } catch (error) { status.textContent = 'Không tải được dòng vật tư: ' + error.message; more.hidden = false; more.textContent = 'Thử lại'; }
    finally { more.disabled = false; }
  }
  if (header.id == null) {
    status.textContent = 'Không xác định được chứng từ để tải Sales Lines.';
    return;
  }
  await load();
}

async function exportSalesReport(table, row, button) {
  const reportWindow = window.open('', '_blank');
  if (!reportWindow) {
    notifyCustomer('Trình duyệt đã chặn cửa sổ báo cáo. Hãy cho phép cửa sổ bật lên rồi thử lại.');
    return;
  }
  reportWindow.document.write('<!doctype html><html lang="vi"><title>Đang tạo báo cáo…</title><body style="font:16px Arial;padding:32px">Đang tải dữ liệu báo cáo…</body></html>');
  reportWindow.document.close();
  if (button) button.disabled = true;

  const escape = value => String(value ?? '').replace(/[&<>"']/g, char => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  })[char]);
  const text = value => value == null || value === '' ? '—' : escape(value);
  const date = value => {
    if (!value) return '—';
    const parsed = new Date(`${String(value).slice(0, 10)}T00:00:00`);
    return Number.isNaN(parsed.valueOf()) ? escape(value)
      : new Intl.DateTimeFormat('vi-VN', {day: '2-digit', month: 'long', year: 'numeric'}).format(parsed);
  };
  const minorUnits = value => {
    const match = String(value ?? '0').match(/^(-?)(\d+)(?:\.(\d+))?$/);
    if (!match) return 0n;
    const [, sign, whole, fraction = ''] = match;
    const cents = BigInt(whole) * 100n + BigInt((fraction + '00').slice(0, 2));
    return sign ? -cents : cents;
  };
  const amount = cents => erpDecimal(`${cents < 0n ? '-' : ''}${(cents < 0n ? -cents : cents) / 100n}.${String((cents < 0n ? -cents : cents) % 100n).padStart(2, '0')}`, true);
  const addressLines = (prefix, details, fallback = {}) => [
    details[`${prefix}_name`] || fallback.name,
    details[`${prefix}_address`] || (prefix === 'sell_to' ? fallback.address : ''),
    details[`${prefix}_address_2`],
    [details[`${prefix}_city`], details[`${prefix}_state`], details[`${prefix}_post_code`]].filter(Boolean).join(', '),
    details[`${prefix}_country_code`],
    details[`${prefix}_phone`] || (prefix === 'sell_to' ? fallback.phone : ''),
    details[`${prefix}_email`] || (prefix === 'sell_to' ? fallback.email : '')
  ].filter(Boolean);

  try {
    const query = new URLSearchParams({table, id: String(row.id)});
    const details = await erpGet('/sales-form/values?' + query);
    const relatedOrderId = row.sales_order_id || row.related_sales_order_id;
    const sourceOrderResult = relatedOrderId
      ? await erpGet('/tables/sales_orders?' + new URLSearchParams({
        page: '0', size: '1', filterColumn: 'id', filterValue: String(relatedOrderId)
      }))
      : {rows: []};
    const sourceOrder = sourceOrderResult.rows[0] || {};
    const customerId = row.customer_id || sourceOrder.customer_id;
    const locationId = row.location_id || sourceOrder.location_id;
    const customerQuery = new URLSearchParams({
      page: '0', size: '1', filterColumn: 'id', filterValue: String(customerId || '')
    });
    const [customerResult, locationResult] = await Promise.all([
      customerId ? erpGet('/tables/customers?' + customerQuery) : Promise.resolve({rows: []}),
      locationId ? erpGet('/tables/locations?' + new URLSearchParams({
        page: '0', size: '1', filterColumn: 'id', filterValue: String(locationId)
      })) : Promise.resolve({rows: []})
    ]);
    const customer = customerResult.rows[0] || {};
    const location = locationResult.rows[0] || {};
    const headerId = details._header_id || row.id;
    const lines = [];
    let page = 0;
    let totalLines = 0;
    do {
      const result = await erpGet('/tables/sales_document_lines?' + new URLSearchParams({
        page: String(page), size: '100', filterColumn: 'document_id', filterValue: String(headerId)
      }));
      lines.push(...result.rows);
      totalLines = result.total;
      page++;
    } while (lines.length < totalLines);

    const documentType = row.document_type || ({
      sales_orders: 'ORDER',
      sales_invoices: 'INVOICE',
      sales_returns: 'RETURN_ORDER'
    }[table] || 'ORDER');
    const documentKind = {
      ORDER: 'Sales Order',
      QUOTE: 'Sales Quote',
      INVOICE: 'Sales Invoice',
      RETURN_ORDER: 'Sales Return Order'
    }[documentType] || 'Sales Document';
    const documentNo = row.invoice_no || row.return_no || row.order_no || row.quote_no
      || details.quote_no || row.document_no || `#${row.id}`;
    const sellTo = addressLines('sell_to', details, customer);
    const shipTo = addressLines('ship_to', details);
    const printableLines = lines.map((line, index) => `
      <tr>
        <td class="number">${escape(line.line_no || index + 1)}</td>
        <td><span class="item-no">${text(line.item_no)}</span></td>
        <td>${text(line.description)}</td>
        <td class="number">${text(erpDecimal(line.quantity))}</td>
        <td>${text(line.unit_of_measure)}</td>
        <td class="number">${text(erpDecimal(line.unit_price, true))}</td>
        <td class="number">${text(erpDecimal(line.discount_percent))}%</td>
        <td class="number">${text(erpDecimal(line.vat_percent))}%</td>
        <td class="number strong">${text(erpDecimal(line.line_amount_ex_vat, true))}</td>
      </tr>`).join('');
    const subtotal = lines.length
      ? lines.reduce((sum, line) => sum + minorUnits(line.line_amount_ex_vat), 0n)
      : minorUnits(details.subtotal);
    const discounts = lines.length
      ? lines.reduce((sum, line) => sum + minorUnits(line.discount_amount), 0n)
      : minorUnits(details.discount_amount);
    const vat = lines.length
      ? lines.reduce((sum, line) => sum + minorUnits(line.vat_amount), 0n)
      : minorUnits(details.vat_amount);
    const total = lines.length
      ? lines.reduce((sum, line) => sum + minorUnits(line.line_amount), 0n)
      : minorUnits(details.total_amount);
    const generationDate = new Intl.DateTimeFormat('vi-VN', {
      day: '2-digit', month: 'long', year: 'numeric'
    }).format(new Date());
    const note = details.work_description || row.notes;

    reportWindow.document.open();
    reportWindow.document.write(`<!doctype html>
      <html lang="vi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
      <title>${escape(documentKind)} ${escape(documentNo)}</title>
      <style>${documentReportStyles}</style></head><body>
      <div class="toolbar"><button class="secondary" onclick="window.close()">Đóng</button><button onclick="window.print()">In / Lưu PDF</button></div>
      <main class="sheet">
        <header class="top"><div class="brand"><div class="logo">E</div><div><p class="eyebrow">ERP Enterprise · Sales</p><h1>Chứng từ bán hàng</h1><p class="company">Báo cáo giao dịch khách hàng</p></div></div>
          <div class="doc-meta"><strong>${text(documentNo)}</strong>${escape(documentKind)}<br>Ngày xuất: ${escape(generationDate)}</div></header>
        <section class="title"><h2>${escape(documentKind)}</h2><span class="badge">${text(row.status)}</span></section>
        <section class="addresses"><article class="address"><h3>Khách hàng · Bill-to</h3><strong>${text(sellTo[0] || customer.name || row.sell_to_name)}</strong>${sellTo.slice(1).map(value => `<p>${escape(value)}</p>`).join('')}</article>
          <article class="address"><h3>Giao hàng · Ship-to</h3><strong>${text(shipTo[0] || details.ship_to_name || sellTo[0] || customer.name)}</strong>${(shipTo.length ? shipTo.slice(1) : sellTo.slice(1)).map(value => `<p>${escape(value)}</p>`).join('')}</article></section>
        <section class="facts">
          <div class="fact"><span>Mã khách hàng</span><strong>${text(row.customer_no || customer.customer_no)}</strong></div>
          <div class="fact"><span>Đơn hàng liên quan</span><strong>${text(row.related_document_no || sourceOrder.order_no || row.order_no || documentNo)}</strong></div>
          <div class="fact"><span>Ngày chứng từ</span><strong>${date(details.document_date || row.document_date || row.order_date)}</strong></div>
          <div class="fact"><span>Ngày ghi sổ</span><strong>${date(row.posting_date || details.posting_date)}</strong></div>
          <div class="fact"><span>Ngày giao hàng</span><strong>${date(details.shipment_date || row.shipment_date || row.requested_delivery_date)}</strong></div>
          <div class="fact"><span>Hạn thanh toán</span><strong>${date(details.due_date)}</strong></div>
          <div class="fact"><span>Điều khoản thanh toán</span><strong>${text(details.payment_terms_code || row.payment_terms_code)}</strong></div>
          <div class="fact"><span>Nhân viên phụ trách</span><strong>${text(details.salesperson_code || row.salesperson_code)}</strong></div>
          <div class="fact"><span>Địa điểm</span><strong>${text(erpLocationCode(row.location_code || location.code))}</strong></div>
        </section>
        <div class="table-wrap"><table><thead><tr><th>Dòng</th><th>Mã Item</th><th>Mô tả</th><th class="number">Số lượng</th><th>ĐVT</th><th class="number">Đơn giá</th><th class="number">CK</th><th class="number">VAT</th><th class="number">Thành tiền</th></tr></thead>
          <tbody>${printableLines || '<tr><td colspan="9" class="empty">Chứng từ chưa có dòng hàng.</td></tr>'}</tbody></table></div>
        <section class="summary-wrap"><div class="summary"><div class="sum-row"><span>Tiền hàng sau chiết khấu</span><strong>${amount(subtotal)}</strong></div><div class="sum-row"><span>Chiết khấu</span><strong>${amount(discounts)}</strong></div><div class="sum-row"><span>Thuế VAT</span><strong>${amount(vat)}</strong></div><div class="sum-row total"><span>Tổng thanh toán · ${text(details.currency_code || row.currency_code || 'VND')}</span><span>${amount(total)}</span></div></div></section>
        ${note ? `<aside class="note"><strong>Ghi chú</strong><br>${escape(note).replace(/\n/g, '<br>')}</aside>` : ''}
        <footer class="footer"><span>${escape(documentKind)} · ${escape(documentNo)}</span><span>Được tạo từ ERP Enterprise</span></footer>
      </main></body></html>`);
    reportWindow.document.close();
    reportWindow.focus();
  } catch (error) {
    reportWindow.document.body.textContent = `Không thể tạo báo cáo: ${error.message}`;
    notifyCustomer(`Không thể tạo báo cáo: ${error.message}`);
  } finally {
    if (button) button.disabled = false;
  }
}
