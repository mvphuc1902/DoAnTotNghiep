function salesDocumentLines(form, isQuote = false) {
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
  ['Vật tư', 'Đơn vị', 'Số lượng', 'Đơn giá (VND)', 'CK %', 'VAT %','Thành tiền chưa VAT' ,'Thành tiền (VAT)', ''].forEach(caption => headers.append(node('th', caption)));
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
  const section = erpElement('section', null, 'profile-section');
  section.append(erpElement('h3', 'Sales Lines'));
  const status = erpElement('p', 'Đang tải dòng vật tư…'); section.append(status); dialog.append(section);
  if (table === 'sales_headers' && header.document_type === 'QUOTE' && !header.sales_order_id) section.append(erpButton('Make Order', event => makeSalesQuoteOrder(header, event.currentTarget)));
  const documentType = table === 'sales_orders' ? 'ORDER' : header.document_type;
  const orderId = table === 'sales_orders' ? header.id : header.sales_order_id;
  const lineTable = {
    QUOTE: 'sales_quote_lines',
    ORDER: 'sales_order_lines',
    RETURN_ORDER: 'sales_return_lines',
    INVOICE: 'sales_invoice_lines'
  }[documentType];
  const lineFilter = {
    sales_quote_lines: ['sales_header_id', header.id],
    sales_order_lines: ['sales_order_id', orderId],
    sales_return_lines: ['return_id', header.sales_return_id],
    sales_invoice_lines: ['invoice_id', header.sales_invoice_id]
  }[lineTable];
  if (table === 'sales_headers') {
    if (lineFilter) section.append(erpButton('Quản lý Sales Lines', () =>
      openErpTable(lineTable, lineFilter[0], lineFilter[1])));
  }
  const wrapper = erpElement('div', null, 'overflow-auto');
  const grid = erpElement('table', null, 'w-full text-sm text-left');
  const head = erpElement('tr');
  ['Thao tác', 'Vật tư', 'Mô tả', 'Đơn vị', 'Số lượng', 'Đơn giá', 'CK %', 'VAT %'].forEach(title => head.append(erpElement('th', title, 'p-2')));
  const thead = erpElement('thead'); thead.append(head); const body = erpElement('tbody'); grid.append(thead, body); wrapper.append(grid); section.append(wrapper);
  let page = 0;
  const more = erpButton('Tải thêm dòng', () => load(false)); more.hidden = true; section.append(more);
  async function canEditLines() {
    if (documentType === 'QUOTE') return header.status === 'DRAFT' && !header.sales_order_id;
    if (documentType === 'INVOICE') return true;
    if (documentType === 'ORDER') {
      const result = await erpGet('/tables/sales_orders?size=1&filterColumn=id&filterValue=' + encodeURIComponent(orderId));
      return result.rows[0]?.status === 'OPEN';
    }
    if (documentType === 'RETURN_ORDER') {
      const result = await erpGet('/tables/sales_returns?size=1&filterColumn=id&filterValue=' + encodeURIComponent(header.sales_return_id));
      return result.rows[0]?.status === 'DRAFT';
    }
    return false;
  }
  async function load(reset = true) {
    if (reset) { body.replaceChildren(); page = 0; }
    more.disabled = true;
    try {
      const result = table === 'sales_headers'
        ? await erpGet('/sales-quotes/' + encodeURIComponent(header.id) + '/lines')
        : await erpGet('/tables/sales_order_lines?size=100&page=' + page + '&filterColumn=sales_order_id&filterValue=' + encodeURIComponent(orderId));
      let editable = false, editabilityError = '';
      try { editable = await canEditLines(); }
      catch (error) { editabilityError = error.message || 'Không tải được trạng thái chứng từ.'; }
      const lines = Array.isArray(result) ? result : result.rows;
      lines.forEach(line => {
        const row = erpElement('tr');
        const actions = erpElement('td', null, 'p-2 whitespace-nowrap');
        const edit = erpButton('Sửa', async event => {
          event.stopPropagation();
          try {
            const source = await erpGet(`/tables/${encodeURIComponent(lineTable)}?size=1&filterColumn=id&filterValue=${encodeURIComponent(line.id)}`);
            if (!source.rows.length) throw new Error('Sales Line không còn tồn tại.');
            await openErpForm(lineTable, source.rows[0], () => load());
          } catch (error) { status.textContent = error.message; }
        });
        if (!editable || line.id == null) {
          edit.disabled = true;
          edit.title = line.id == null
            ? 'Không thể sửa vì dòng này chưa được lưu.'
            : editabilityError ? `Không kiểm tra được trạng thái chứng từ: ${editabilityError}`
            : 'Chỉ sửa được dòng khi chứng từ còn ở trạng thái có thể chỉnh sửa.';
        }
        actions.append(edit);
        if (line.id != null) actions.append(salesLineDeleteButton('Xóa', event => {
          event.stopPropagation();
          deleteErpRow(lineTable, {id: line.id}, event.currentTarget, () => load());
        }));
        row.append(actions);
        ['item_no', 'description', 'unit_of_measure', 'quantity', 'unit_price', 'discount_percent', 'vat_percent'].forEach(key => row.append(erpElement('td', erpValue(key === 'item_no' ? line.item_no ?? line.item_id : line[key], key), 'p-2')));
        body.append(row);
      });
      page++;
      status.textContent = body.children.length ? `${body.children.length} dòng vật tư` : 'Chưa có dòng vật tư.';
      more.hidden = Array.isArray(result) || page * 100 >= result.total;
    } catch (error) { status.textContent = 'Không tải được dòng vật tư.'; more.hidden = false; more.textContent = 'Thử lại'; }
    finally { more.disabled = false; }
  }
  if (!lineTable || !lineFilter || lineFilter.some(value => value == null)) {
    status.textContent = 'Không xác định được bảng Sales Lines của chứng từ.';
    return;
  }
  await load();
}
