function purchaseDocumentLines(form, controls, initialType) {
  const section = erpElement('details', null, 'document-section sales-document');
  section.open = true;
  section.append(erpElement('summary', 'Purchase Lines'));
  const tools = erpElement('div', null, 'sales-line-tools');
  const search = erpElement('input');
  search.type = 'search';
  search.placeholder = initialType === 'INVOICE' ? 'Tìm mã phiếu nhập hoặc vật tư…' : 'Tìm mã hoặc tên vật tư…';
  search.setAttribute('aria-label', 'Tìm dòng mua hàng');
  const picker = erpElement('select');
  picker.setAttribute('aria-label', initialType === 'INVOICE' ? 'Chọn dòng phiếu nhập' : 'Chọn mặt hàng');
  const add = erpButton('+ Thêm dòng', () => {
    const selected = choices.find(choice => String(choice.id) === picker.value);
    if (selected) {
      addLine(selected);
      picker.value = '';
      picker.dispatchEvent(new Event('change', {bubbles: true}));
    }
  });
  add.disabled = true;
  tools.append(search, picker, add);
  const message = erpElement('p', 'Chọn nhà cung cấp để tải dữ liệu.', 'document-hint');
  message.setAttribute('role', 'status');
  const scroll = erpElement('div', null, 'sales-lines-scroll');
  const table = erpElement('table');
  const head = erpElement('thead');
  const heading = erpElement('tr');
  ['Mặt hàng / Phiếu nhập', 'Đơn vị', 'Kho', 'Số lượng', 'Đơn giá (VND)', 'CK %', 'VAT %', 'Thành tiền', ''].forEach(caption => heading.append(erpElement('th', caption)));
  head.append(heading);
  const body = erpElement('tbody');
  table.append(head, body);
  scroll.append(table);
  const empty = erpElement('p', 'Chưa có Purchase Lines.', 'document-hint');
  section.append(tools, message, scroll, empty);
  (form.querySelector('.document-content') || form).append(section);

  const rows = [];
  let locations = [];
  let units = [];
  let locationsReady = false;
  const locationStatus = erpElement('p', 'Đang tải danh sách kho…', 'document-hint');
  const retryLocations = erpButton('Tải lại kho', () => loadLocations());
  retryLocations.hidden = true;
  section.append(locationStatus, retryLocations);
  function fillLocations(row) {
    const selected = row.locationCode.value || row.initialLocation;
    row.locationCode.replaceChildren();
    const emptyOption = erpElement('option', 'Chọn kho…');
    emptyOption.value = '';
    row.locationCode.append(emptyOption);
    locations.forEach(location => {
      const option = erpElement('option', location.name || location.code);
      option.value = location.code;
      row.locationCode.append(option);
    });
    if (initialType === 'INVOICE' && selected && !locations.some(location => location.code === selected)) {
      const option = erpElement('option', selected + ' · Kho trên phiếu nhập');
      option.value = selected;
      row.locationCode.append(option);
    }
    row.locationCode.value = selected || '';
    row.locationCode.disabled = initialType === 'INVOICE' || !locationsReady;
  }
  function fillUnits(row) {
    const selected = row.unitSelect.value || row.unit;
    row.unitSelect.replaceChildren();
    const empty = erpElement('option', 'Chọn đơn vị…'); empty.value = ''; row.unitSelect.append(empty);
    units.forEach(unit => {
      const option = erpElement('option', unit.name || unit.code);
      option.value = unit.code; row.unitSelect.append(option);
    });
    if (initialType === 'INVOICE' && selected && !units.some(unit => unit.code === selected)) {
      const option = erpElement('option', selected); option.value = selected; row.unitSelect.append(option);
    }
    row.unitSelect.value = selected;
    row.unitSelect.disabled = initialType === 'INVOICE' || !locationsReady;
  }
  async function loadLocations() {
    locationsReady = false;
    retryLocations.hidden = true;
    locationStatus.textContent = 'Đang tải danh sách kho…';
    rows.forEach(row => { row.locationCode.disabled = true; });
    try {
      const loaded = [];
      for (let page = 0; ; page++) {
        const data = await erpGet('/tables/locations?' + new URLSearchParams({size: '100', page: String(page)}));
        loaded.push(...data.rows);
        if (data.rows.length < 100) break;
      }
      const loadedUnits = [];
      for (let page = 0; ; page++) {
        const data = await erpGet('/tables/units_of_measure?' + new URLSearchParams({size: '100', page: String(page)}));
        loadedUnits.push(...data.rows.filter(unit => unit.active !== false));
        if (data.rows.length < 100) break;
      }
      units = loadedUnits;
      locations = loaded;
      locationsReady = true;
      rows.forEach(row => { fillLocations(row); fillUnits(row); });
      locationStatus.textContent = initialType === 'INVOICE'
        ? 'Kho lấy từ dòng Purchase Receipt đã chọn.'
        : locations.length ? 'Chọn kho từ Locations cho từng dòng; có thể khác kho trên chứng từ.' : 'Chưa có kho. Tạo kho trong Locations rồi tải lại.';
      retryLocations.hidden = locations.length > 0;
    } catch (error) {
      locationStatus.textContent = 'Không tải được kho hoặc đơn vị: ' + error.message;
      retryLocations.hidden = false;
    }
  }
  loadLocations();
  let choices = [];
  let version = 0;
  let timer;
  const fixed = (value, scale) => {
    if (!/^\d+(\.\d+)?$/.test(value)) return 0n;
    const [whole, part = ''] = value.split('.');
    return BigInt(whole + part.padEnd(scale, '0').slice(0, scale));
  };
  const round = (value, divisor) => (value + divisor / 2n) / divisor;
  const money = cents => erpDecimal(`${cents / 100n}.${String(cents % 100n).padStart(2, '0')}`, true);
  function update() {
    let net = 0n, tax = 0n;
    rows.forEach(row => {
      let price = 0n;
      try { price = fixed(erpNormalizePrice(row.unitPrice.value), 2); } catch { /* Invalid input is reported on save. */ }
      const gross = round(fixed(row.quantity.value, 3) * price, 1000n);
      const discount = round(gross * fixed(row.discountPercent.value, 2), 10000n);
      const base = gross - discount;
      const vat = round(base * fixed(row.vatPercent.value, 2), 10000n);
      net += base;
      tax += vat;
      row.amount.textContent = money(base + vat);
    });
    total.textContent = `Tổng cộng: ${money(net + tax)}`;
    empty.hidden = rows.length > 0;
  }
  const total = erpElement('p', 'Tổng cộng: 0 ₫', 'font-semibold text-right');
  section.append(total);
  function addLine(choice) {
    const description = initialType === 'INVOICE'
      ? `${choice.receipt_no} · ${choice.item_no || ''} · ${choice.description || ''}`
      : `${choice.item_no || ''} · ${choice.description || ''}`;
    const row = {
      id: String(choice.id),
      receiptLineId: initialType === 'INVOICE' ? String(choice.id) : null,
      itemId: initialType === 'INVOICE' ? null : String(choice.id),
      description,
      unit: choice.unit_of_measure || choice.base_unit_of_measure || '',
      vat: String(choice.vat_percent ?? 0),
      discount: String(choice.discount_percent ?? 0)
    };
    const tr = erpElement('tr');
    tr.append(erpElement('td', description));
    row.unitSelect = erpElement('select');
    row.unitSelect.required = initialType !== 'INVOICE';
    row.unitSelect.setAttribute('aria-label', 'Đơn vị — ' + description);
    fillUnits(row);
    const unitCell = erpElement('td'); unitCell.append(row.unitSelect); tr.append(unitCell);
    const locationCell = erpElement('td');
    row.locationCode = erpElement('select');
    row.locationCode.required = initialType !== 'INVOICE';
    row.locationCode.setAttribute('aria-label', 'Kho — ' + description);
    row.initialLocation = initialType === 'INVOICE' ? (choice.location_code || '') : (controls.get('location_code')?.value || '');
    fillLocations(row);
    locationCell.append(row.locationCode);
    tr.append(locationCell);
    [
      ['quantity', 'Số lượng', initialType === 'INVOICE' ? choice.remaining_quantity : '1', '0.001'],
      ['unitPrice', 'Đơn giá', choice.unit_price ?? '0', '0.01'],
      ['discountPercent', 'Chiết khấu', row.discount, '0.01'],
      ['vatPercent', 'VAT', row.vat, '0.01']
    ].forEach(([key, label, value, step]) => {
      const cell = erpElement('td');
      const input = erpElement('input');
      input.type = key === 'unitPrice' ? 'text' : 'number';
      input.value = value ?? '';
      input.required = true;
      input.min = key === 'discountPercent' || key === 'vatPercent' ? '0' : key === 'unitPrice' ? '0' : '0.001';
      input.step = step;
      if (key === 'discountPercent' || key === 'vatPercent') input.max = '100';
      if (key === 'unitPrice') erpConfigurePriceInput(input);
      input.setAttribute('aria-label', `${label} — ${description}`);
      input.addEventListener('input', update);
      row[key] = input;
      cell.append(input);
      tr.append(cell);
    });
    row.amount = erpElement('td');
    tr.append(row.amount);
    const removeCell = erpElement('td');
    const remove = erpButton('Xóa', () => {
      rows.splice(rows.indexOf(row), 1);
      tr.remove();
      update();
    });
    remove.setAttribute('aria-label', `Xóa dòng ${description}`);
    removeCell.append(remove);
    tr.append(removeCell);
    rows.push(row);
    body.append(tr);
    update();
    row.quantity.focus();
  }
  async function lookup() {
    const request = ++version;
    const vendorId = controls.get('vendor_id')?.value;
    choices = [];
    picker.replaceChildren();
    const placeholder = erpElement('option', 'Chọn dòng để thêm…');
    placeholder.value = '';
    picker.append(placeholder);
    picker.disabled = true;
    add.disabled = true;
    if (initialType === 'INVOICE' && !vendorId) {
      message.textContent = 'Chọn nhà cung cấp trước khi chọn phiếu nhập.';
      return;
    }
    message.textContent = 'Đang tải dữ liệu…';
    try {
      const params = new URLSearchParams({size: '100', search: search.value.trim()});
      let result;
      if (initialType === 'INVOICE') {
        params.set('vendorId', vendorId);
        result = {rows: await erpGet('/purchase-receipt-lines/available?' + params)};
        choices = result.rows.map(line => ({...line, id: line.receipt_line_id}));
      } else {
        result = await erpGet('/tables/items?' + params);
        choices = result.rows.filter(item => !item.blocked);
      }
      if (request !== version) return;
      picker.replaceChildren(placeholder);
      choices.forEach(choice => {
        const caption = initialType === 'INVOICE'
          ? `${choice.receipt_no} · ${choice.item_no || ''} · ${choice.description || ''} · Còn ${choice.remaining_quantity}`
          : `${choice.item_no || ''} · ${choice.description || ''}`;
        const option = erpElement('option', caption);
        option.value = choice.id;
        picker.append(option);
      });
      picker.disabled = false;
      message.textContent = choices.length
        ? `${choices.length} ${initialType === 'INVOICE' ? 'dòng nhập còn số lượng' : 'mặt hàng'} khả dụng.`
        : initialType === 'INVOICE' ? 'Không còn dòng phiếu nhập chưa lập hóa đơn cho nhà cung cấp này.'
          : 'Không có mặt hàng phù hợp.';
    } catch (error) {
      if (request === version) message.textContent = `Không tải được danh sách: ${error.message}`;
    }
  }
  search.addEventListener('input', () => {
    clearTimeout(timer);
    timer = setTimeout(lookup, 250);
  });
  picker.addEventListener('change', () => {
    add.disabled = !choices.some(choice => String(choice.id) === picker.value);
  });
  controls.get('vendor_id')?.addEventListener('change', () => {
    if (initialType === 'INVOICE' && rows.length) {
      notifyCustomer('Đã có dòng hóa đơn; không thể đổi nhà cung cấp.');
      controls.get('vendor_id').value = '';
      return;
    }
    lookup();
  });
  lookup();

  return () => {
    if (!rows.length) {
      section.open = true;
      search.focus();
      throw new Error('Hãy thêm ít nhất một Purchase Line.');
    }
    if (initialType !== 'INVOICE' && !locationsReady) throw new Error('Hãy tải danh sách kho trước khi lưu.');
    return rows.map(row => {
      if (initialType !== 'INVOICE' && !locations.some(location => location.code === row.locationCode.value)) {
        row.locationCode.focus();
        throw new Error('Chọn kho hợp lệ cho ' + row.description);
      }
      if (initialType !== 'INVOICE' && !units.some(unit => unit.code === row.unitSelect.value)) {
        row.unitSelect.focus(); throw new Error('Chọn đơn vị từ Unit cho ' + row.description);
      }
      let unitPrice;
      try { unitPrice = erpNormalizePrice(row.unitPrice.value); }
      catch {
        row.unitPrice.focus();
        throw new Error(`Nhập đơn giá hợp lệ cho ${row.description}, ví dụ 1.500.000.`);
      }
      const quantity = row.quantity.value.trim();
      if (!/^\d+(\.\d{1,3})?$/.test(quantity) || Number(quantity) <= 0) {
        row.quantity.focus();
        throw new Error(`Số lượng của ${row.description} phải lớn hơn 0, tối đa 3 chữ số thập phân.`);
      }
      return {
        receiptLineId: row.receiptLineId,
        itemId: row.itemId,
        description: row.description,
        unitOfMeasure: row.unitSelect.value || null,
        quantity,
        unitPrice,
        discountPercent: row.discountPercent.value || '0',
        vatPercent: row.vatPercent.value || '0',
        locationCode: row.locationCode.value || null
      };
    });
  };
}

async function savePurchaseDocument(header, lines) {
  const response = await fetch(erpApi + '/purchase-documents/with-lines', {
    method: 'POST',
    headers: {Accept: 'application/json', 'Content-Type': 'application/json'},
    body: JSON.stringify({header, lines}),
    signal: AbortSignal.timeout(30000)
  });
  const result = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(result.message || `Không lưu được Purchase Document (HTTP ${response.status}).`);
  return result;
}
