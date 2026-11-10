const erpEditDialog = erpElement('dialog', null,
  'rounded-xl p-6 w-[min(850px,95vw)] max-h-[85vh] overflow-auto shadow-xl backdrop:bg-slate-900/50');
erpEditDialog.id = 'erp-edit-dialog';
erpEditDialog.setAttribute('aria-labelledby', 'erp-edit-title');
document.body.append(erpEditDialog);
let erpSaving = false;
let erpFormVersion = 0;
erpEditDialog.addEventListener('cancel', event => { if (erpSaving) event.preventDefault(); });

async function erpWrite(table, id, method, values, releaseReservations = false) {
  let response;
  try {
    response = await fetch(erpApi + '/tables/' + encodeURIComponent(table) + (id == null ? '' : '/' + encodeURIComponent(id)) + (table === 'inventory' && method === 'DELETE' && releaseReservations ? '?releaseReservations=true' : ''), {
      method, headers: {Accept: 'application/json', 'Content-Type': 'application/json'},
      body: values === undefined ? undefined : JSON.stringify(values), signal: AbortSignal.timeout(20000)
    });
  } catch (error) {
    throw new Error('Chưa nhận được kết quả từ máy chủ. Tải lại danh sách để kiểm tra trước khi gửi lại.');
  }
  if (!response.ok) {
    const data = await response.json().catch(() => ({}));
    throw new Error(data.message || `Không thực hiện được thao tác (HTTP ${response.status}).`);
  }
  return response.status === 204 ? null : response.json();
}

function erpDefault(field) {
  const value = field.defaultValue;
  if (!value) return '';
  if (value === 'true' || value === 'false' || /^-?\d+(\.\d+)?$/.test(value)) return value;
  const quoted = value.match(/^'([^']*)'::/);
  return quoted ? quoted[1] : '';
}

function salesSourceLineEditor(table, controls) {
  if (!['sales_invoices', 'sales_returns'].includes(table)) return null;
  const isInvoice = table === 'sales_invoices';
  const order = controls.get('sales_order_id');
  const section = erpElement('details', null, 'document-section sales-document sales-source-lines');
  section.open = true;
  section.dataset.salesSourceLines = 'true';
  section.append(erpElement('summary', 'Sales Lines'));
  const tools = erpElement('div', null, 'sales-line-tools');
  const category = erpElement('select');
  category.setAttribute('aria-label', 'Loại Item');
  [['', 'Tất cả loại Item'], ['INVENTORY', 'Inventory · Hàng tồn kho'],
    ['SERVICE', 'Service · Dịch vụ'], ['NON_INVENTORY', 'Non-Inventory · Không quản lý tồn']]
    .forEach(([value, label]) => {
      const option = erpElement('option', label);
      option.value = value;
      category.append(option);
    });
  const search = erpElement('input');
  search.type = 'search';
  search.placeholder = 'Tìm mã hoặc tên trong Items…';
  search.setAttribute('aria-label', 'Tìm Item trong bảng Items');
  const picker = erpElement('select');
  picker.setAttribute('aria-label', 'Chọn Item từ bảng Items');
  const placeholder = erpElement('option', 'Tra cứu Item từ bảng Items…');
  placeholder.value = '';
  picker.append(placeholder);
  const add = erpButton('+ Thêm dòng', () => {
    const sourceId = picker.value.startsWith('line:') ? picker.value.slice(5) : '';
    const source = sourceLines.find(line => String(line.id) === sourceId);
    if (source && !rows.some(row => String(row.source.id) === String(source.id))) addLine(source);
    picker.value = '';
    picker.dispatchEvent(new Event('change', {bubbles: true}));
  });
  add.disabled = true;
  tools.append(category, search, picker, add);
  section.append(tools);
  const status = erpElement('p', 'Chọn Sales Order rồi tìm Item trong bảng Items.', 'document-hint');
  status.setAttribute('role', 'status');
  section.append(status);
  const tableWrap = erpElement('div', null, 'sales-lines-scroll');
  const tableElement = erpElement('table', null, 'sales-document');
  const head = erpElement('thead', null, 'bg-slate-100');
  const headerRow = erpElement('tr');
  const captions = ['Vật tư', 'Location Code', 'Đơn vị', 'Số lượng', 'Đơn giá (VND)', 'CK %', 'VAT %',
    'Thành tiền chưa VAT', 'Thành tiền (VAT)', ''];
  captions.forEach(caption => headerRow.append(erpElement('th', caption, 'p-2')));
  head.append(headerRow);
  const body = erpElement('tbody', null, 'divide-y');
  tableElement.append(head, body);
  tableWrap.append(tableElement);
  section.append(tableWrap);
  const empty = erpElement('p', 'Chưa có Sales Lines. Tra cứu Item trong Sales Order rồi bấm Thêm dòng.', 'document-hint');
  section.append(empty);
  const totals = erpElement('dl', null, 'sales-totals');
  const amounts = {};
  [['net', 'Tổng trước VAT'], ['discount', 'Chiết khấu'], ['vat', 'Thuế VAT'], ['total', 'Tổng thanh toán (VND)']].forEach(([key, label]) => {
    amounts[key] = erpElement('dd', '0 ₫');
    totals.append(erpElement('dt', label), amounts[key]);
  });
  section.append(totals);
  section.append(erpElement('p', isInvoice
    ? 'Chọn dòng từ Sales Order để tính tổng hóa đơn. Số lượng không vượt phần còn lại của đơn bán.'
    : 'Chọn dòng từ Sales Order để nhập hàng trả. Tồn kho chỉ được cập nhật khi xác nhận nhận hàng.',
  'document-hint'));
  let rows = [];
  let sourceLines = [];
  let catalogItems = [];
  let requestVersion = 0;
  const fixed = (value, scale) => {
    const normalized = String(value ?? '0');
    if (!/^\d+(\.\d+)?$/.test(normalized)) return 0n;
    const [whole, fraction = ''] = normalized.split('.');
    return BigInt(whole + fraction.padEnd(scale, '0').slice(0, scale));
  };
  const money = cents => erpDecimal(`${cents / 100n}.${String(cents % 100n).padStart(2, '0')}`, true);
  function updateTotals() {
    let net = 0n, discountTotal = 0n, vatTotal = 0n;
    rows.forEach(row => {
      const quantity = row.quantity.value;
      const gross = (fixed(quantity, 3) * fixed(row.source.unit_price, 2) + 500n) / 1000n;
      const discount = (gross * fixed(row.source.discount_percent, 2) + 5000n) / 10000n;
      const base = gross - discount;
      const vatPercent = row.vat ? row.vat.value : String(row.source.vat_percent ?? '0');
      const tax = (base * fixed(vatPercent, 2) + 5000n) / 10000n;
      row.baseAmount.textContent = money(base);
      row.amount.textContent = money(base + tax);
      net += base; discountTotal += discount; vatTotal += tax;
    });
    amounts.net.textContent = money(net);
    amounts.discount.textContent = money(discountTotal);
    amounts.vat.textContent = money(vatTotal);
    amounts.total.textContent = money(net + vatTotal);
    empty.hidden = rows.length > 0;
  }
  function renderPicker(matches = sourceLines) {
    picker.replaceChildren();
    const prompt = erpElement('option', 'Chọn Item từ bảng Items…');
    prompt.value = '';
    picker.append(prompt);
    catalogItems.forEach(item => {
      const itemLines = matches.filter(line => String(line.item_id) === String(item.id));
      if (!itemLines.length) {
        const option = erpElement('option',
          `${item.item_no || ''} · ${item.description || ''}${order?.value ? ' · Không có trong Sales Order' : ''}`);
        option.value = `item:${item.id}`;
        option.disabled = Boolean(order?.value);
        picker.append(option);
        return;
      }
      itemLines.forEach(line => {
        const label = `${item.item_no || ''} · ${item.description || line.description || ''}`
            + ` · Dòng ${line.line_no}${line.location_code ? ` · ${erpLocationCode(line.location_code)}` : ''}`;
        const option = erpElement('option', label);
        option.value = `line:${line.id}`;
        option.disabled = rows.some(row => String(row.source.id) === String(line.id));
        picker.append(option);
      });
    });
    add.disabled = !picker.value || !picker.value.startsWith('line:')
      || rows.some(row => `line:${row.source.id}` === picker.value);
  }
  function addLine(source) {
    const tr = erpElement('tr');
    const row = {source};
    const itemCell = erpElement('td',
      `${source.item_no || source.item_id || ''} · ${source.description || source.item_description || ''}`, 'p-2');
    tr.append(itemCell, erpElement('td', erpLocationCode(source.location_code), 'p-2'),
      erpElement('td', source.unit_of_measure || '', 'p-2'));
    const quantityCell = erpElement('td', null, 'p-2');
    const quantity = erpElement('input');
    quantity.type = 'number'; quantity.min = '0.001'; quantity.step = '0.001';
    quantity.max = String(source.quantity); quantity.value = String(source.quantity);
    quantity.className = 'sales-line-quantity';
    quantity.setAttribute('aria-label', `Số lượng ${isInvoice ? 'hóa đơn' : 'trả'} — ${source.description || source.item_id}`);
    quantity.addEventListener('input', updateTotals);
    quantityCell.append(quantity);
    row.quantity = quantity;
    tr.append(quantityCell, erpElement('td', erpPrice(source.unit_price ?? '0'), 'p-2'),
      erpElement('td', erpValue(source.discount_percent ?? '0', 'discount_percent'), 'p-2'));
    const vatCell = erpElement('td', null, 'p-2');
    if (isInvoice) {
      const vat = erpElement('input');
      vat.type = 'number'; vat.min = '0'; vat.max = '100'; vat.step = '0.01';
      vat.value = String(source.vat_percent ?? '0');
      vat.className = 'sales-line-vat';
      vat.setAttribute('aria-label', `VAT % — ${source.description || source.item_id}`);
      vat.addEventListener('input', updateTotals);
      vatCell.append(vat);
      row.vat = vat;
    } else vatCell.textContent = erpValue(source.vat_percent ?? '0', 'vat_percent');
    tr.append(vatCell);
    row.baseAmount = erpElement('td', '0 ₫', 'p-2');
    row.amount = erpElement('td', '0 ₫', 'p-2');
    tr.append(row.baseAmount, row.amount);
    const removeCell = erpElement('td', null, 'p-2');
    const remove = erpButton('Xóa', () => {
      rows.splice(rows.indexOf(row), 1);
      tr.remove();
      updateTotals();
      renderPicker();
    });
    remove.setAttribute('aria-label', `Xóa dòng ${source.description || source.item_no || ''}`);
    removeCell.append(remove);
    tr.append(removeCell);
    rows.push(row);
    body.append(tr);
    updateTotals();
    renderPicker();
    quantity.focus();
  }
  picker.addEventListener('change', () => {
    add.disabled = !picker.value || !picker.value.startsWith('line:')
      || rows.some(row => `line:${row.source.id}` === picker.value);
  });
  let lookupTimer;
  async function lookupItems() {
    const orderId = order?.value;
    const version = ++requestVersion;
    catalogItems = [];
    sourceLines = [];
    picker.replaceChildren(placeholder.cloneNode(true));
    add.disabled = true;
    status.textContent = 'Đang tải danh sách từ bảng Items…';
    try {
      const itemQuery = new URLSearchParams({
        page: '0', size: '100', search: search.value.trim()
      });
      if (category.value) {
        itemQuery.set('filterColumn', 'item_category');
        itemQuery.set('filterValue', category.value);
      }
      const itemResult = await erpGet('/tables/items?' + itemQuery);
      if (version !== requestVersion) return;
      catalogItems = itemResult.rows.filter(item => !item.blocked);
      if (orderId) {
        status.textContent = 'Đang liên kết Item với Sales Order…';
        const orderQuery = new URLSearchParams({orderId});
        const orderResult = await erpGet('/sales-form/order-items?' + orderQuery);
        if (version !== requestVersion) return;
        const catalogItemIds = new Set(catalogItems.map(item => String(item.id)));
        sourceLines = orderResult.filter(line => catalogItemIds.has(String(line.item_id)));
      }
      renderPicker(sourceLines);
      if (!orderId) {
        status.textContent = catalogItems.length
          ? `${catalogItems.length} Item từ bảng Items. Chọn Sales Order để thêm Item vào Sales Lines.`
          : 'Không tìm thấy Item trong bảng Items. Hãy thử từ khóa khác.';
      } else if (itemResult.total > 100) {
        status.textContent = sourceLines.length
          ? `${catalogItems.length} Item đã tra cứu; ${sourceLines.length} dòng khớp với Sales Order.`
          : `${catalogItems.length} Item đã tra cứu; chưa có dòng khớp trong Sales Order.`;
        status.textContent += ' Nhập mã hoặc tên để tìm Item khác.';
      } else {
        status.textContent = sourceLines.length
          ? `${catalogItems.length} Item từ bảng Items; ${sourceLines.length} dòng khớp với Sales Order.`
          : catalogItems.length
            ? 'Item đã tải từ bảng Items nhưng không có Item nào thuộc Sales Order đã chọn.'
            : 'Không tìm thấy Item đang hoạt động trong bảng Items. Hãy thử từ khóa khác.';
      }
    } catch (error) {
      if (version === requestVersion) {
        renderPicker([]);
        add.disabled = true;
        status.textContent = `Không tra cứu được dữ liệu: ${error.message}`;
      }
    }
  }
  const scheduleLookup = () => {
    clearTimeout(lookupTimer);
    requestVersion++;
    catalogItems = [];
    sourceLines = [];
    picker.replaceChildren(placeholder.cloneNode(true));
    add.disabled = true;
    lookupTimer = setTimeout(lookupItems, 200);
  };
  search.addEventListener('input', scheduleLookup);
  category.addEventListener('change', lookupItems);
  order?.addEventListener('change', () => {
    rows = [];
    body.replaceChildren();
    updateTotals();
    lookupItems();
  });
  if (order?.value) lookupItems();
  updateTotals();
  return {
    section,
    read() {
      if (!order?.value) throw new Error('Chọn mã Sales Order trước khi tra cứu Item.');
      if (!rows.length) throw new Error('Hãy tra cứu và thêm ít nhất một Item từ Sales Order.');
      return rows.map(row => {
        const quantity = row.quantity.value;
        if (!/^\d+(\.\d{1,3})?$/.test(quantity) || Number(quantity) <= 0
            || Number(quantity) > Number(row.source.quantity)) {
          throw new Error(`Số lượng trên dòng ${row.source.line_no} phải lớn hơn 0 và không vượt số lượng Sales Order.`);
        }
        if (!isInvoice) return {salesOrderLineId: String(row.source.id), quantity};
        const vatPercent = row.vat.value;
        if (!/^\d+(\.\d{1,2})?$/.test(vatPercent) || Number(vatPercent) > 100) {
          throw new Error(`VAT trên dòng ${row.source.line_no} phải từ 0 đến 100.`);
        }
        return {salesOrderLineId: String(row.source.id), quantity, vatPercent};
      });
    }
  };
}

async function openErpForm(table, row = null, afterSave = null, purchaseType = 'ORDER') {
  if (table === 'item_bom') return openBomDocument(row);
  if (erpSaving) return;
  const version = ++erpFormVersion;
  erpEditDialog.replaceChildren(erpElement('p', 'Đang tải biểu mẫu...'));
  if (!erpEditDialog.open) erpEditDialog.showModal();
  const close = () => { if (!erpSaving) { erpFormVersion++; erpEditDialog.close(); } };
  try {
    const salesDocumentCreate = ['sales_orders', 'sales_headers'].includes(table) && !row;
    const purchaseDocumentCreate = table === 'purchase_headers' && !row;
    if (table === 'purchase_headers' && row) purchaseType = row.document_type;
    const documentCreate = salesDocumentCreate || purchaseDocumentCreate;
    const fullSalesForm = ['sales_orders', 'sales_headers', 'sales_returns', 'sales_invoices'].includes(table);
    const salesDocumentType = row?.document_type || (table === 'sales_headers'
      ? (erpState.filterColumn === 'document_type' ? erpState.filterValue : 'QUOTE')
      : ({sales_orders: 'ORDER', sales_returns: 'RETURN_ORDER', sales_invoices: 'INVOICE'}[table]));
    const fields = (await erpGet('/schema/' + encodeURIComponent(table)))
      .filter(field => !(table === 'sales_headers'
          && ['subtotal', 'discount_amount', 'vat_amount', 'total_amount'].includes(field.name))
        && !(['sales_headers', 'sales_orders', 'sales_invoices', 'sales_returns'].includes(table)
          && field.name === 'notes') && (fullSalesForm || (field.writable &&
        !(table === 'items' && field.name === 'id') &&
        !(table === 'locations' && ['id', 'location_id'].includes(field.name)))))
      .sort((a, b) => table === 'items' ? (a.name === 'item_no' ? 0 : a.name === 'description' ? 1 : 2) - (b.name === 'item_no' ? 0 : b.name === 'description' ? 1 : 2) : 0)
      .map(field => ({...field, writable: field.writable &&
        !(table === 'purchase_headers' && row && !['order_date','posting_date','requested_receipt_date','promised_receipt_date','vendor_reference','external_document_no','payment_terms_code','shipment_method_code','salesperson_code','location_code','notes'].includes(field.name)) &&
        !(table === 'sales_headers' && ['related_sales_order_id', 'sales_return_id', 'sales_invoice_id'].includes(field.name)) &&
        !(table === 'sales_headers' && row && field.name === 'document_type') &&
        !(documentCreate && ['subtotal', 'discount_amount', 'vat_amount', 'total_amount', 'status'].includes(field.name))}));
    if (version !== erpFormVersion || !erpEditDialog.open) return;
    const purchaseTitles = {ORDER: 'Purchase Order', QUOTE: 'Purchase Quote', INVOICE: 'Purchase Invoice', RETURN_ORDER: 'Purchase Return Order'};
    const title = erpElement('h2', table === 'items'
      ? (row ? 'Sửa Item' : 'Tạo Item mới')
      : purchaseDocumentCreate ? 'Thêm ' + (purchaseTitles[purchaseType] || purchaseTitles.ORDER)
        : salesDocumentType && salesDocumentTitles[salesDocumentType]
          ? (row ? 'Sửa ' : 'Thêm ') + salesDocumentTitles[salesDocumentType]
          : (row ? 'Sửa ' : 'Thêm ') + tableLabels[table].toLowerCase(), 'text-xl font-bold');
    title.id = 'erp-edit-title';
    const form = erpElement('form', null, 'space-y-4'); form.id = 'erp-edit-form';
    const grid = erpElement('div', null, 'grid grid-cols-1 sm:grid-cols-2 gap-4');
    const controls = new Map();
    const initialValues = new Map();
    let openingLocation;
    let openingQuantity;
    let locationsReady = false;
    for (const field of fields) {
      const label = erpElement('label', null, 'block text-sm space-y-1');
      const required = field.writable && ((field.required && (row || !field.hasDefault)
          && !(table === 'sales_invoices' && !row && field.name === 'customer_id')) ||
        (!row && table === 'users' && ['name', 'email', 'password'].includes(field.name)));
      const salesItemField=['sales_order_lines','sales_quote_lines','sales_invoice_lines','sales_return_lines'].includes(table)&&field.name==='item_id';
      const productionOrderItemField = table === 'production_orders'
        && ['item_id', 'bom_parent_item_id'].includes(field.name);
      const fieldLabel = salesItemField ? 'No.'
        : productionOrderItemField ? 'Item to manufacture'
        : table === 'production_orders' && field.name === 'quantity' ? 'Số lượng sản xuất'
        : table === 'items' && field.name === 'description' ? 'Tên Item'
        : table === 'items' && field.name === 'item_no' ? 'Mã Item'
        : table === 'sales_returns' && field.name === 'sales_order_id'
        ? 'Mã Sales Order' : columnLabels[field.name] || field.name;
      label.append(erpElement('span', fieldLabel + (required ? ' *' : ''), 'font-medium'));
      const relation = table === 'items' && field.name === 'base_unit_of_measure'
        ? {target_table: 'units_of_measure', target_column: 'code'}
        : field.name === 'unit_of_measure' && table.startsWith('purchase_')
          ? {target_table: 'units_of_measure', target_column: 'code'}
          : field.name === 'location_code' && table.startsWith('purchase_')
            ? {target_table: 'locations', target_column: 'code'}
              : productionOrderItemField ? {target_table: 'items', target_column: 'id'}
                : salesItemField ? {target_table:'items',target_column:'id'}
                : erpCatalog.relations.find(r => r.source_table === table && r.source_column === field.name);
      let input;
      const value = row ? row[field.name] : salesItemField ? '' : table === 'items' && field.name === 'item_category'
        ? 'INVENTORY' : (field.name === erpState.filterColumn ? erpState.filterValue : erpDefault(field));
      if (!field.writable) {
        input = erpElement('input'); input.type = 'text'; input.disabled = true;
        input.placeholder = row ? 'Chưa có dữ liệu' : 'Hệ thống tự cập nhật khi lưu';
      } else if(table==='sales_setup_codes'&&field.name==='category') {
        input=erpElement('select');
        for(const code of ['COUNTRY','CONTACT','CAMPAIGN','OPPORTUNITY','RESPONSIBILITY','CUSTOMER_TEMPLATE','CONTRACT','BANK_ACCOUNT','GEN_BUS','VAT_BUS','CUSTOMER_POSTING','DIMENSION_1','DIMENSION_2','SHIPMENT_METHOD','SHIPPING_AGENT','PAYMENT_TERMS']) {
          const option=erpElement('option',code);option.value=code;input.append(option);
        }
      } else if (relation || field.type === 'boolean' || field.name === 'role' || field.name === 'item_category' || field.name === 'item_type' || field.name === 'document_type' || (['sales_orders', 'production_orders', 'sales_returns'].includes(table) && field.name === 'status')) {
        input = erpElement('select', null);
        const options = relation ? [] : field.name === 'item_category' ? [['INVENTORY', 'Inventory'], ['SERVICE', 'Service'], ['NON_INVENTORY', 'Non-Inventory']]
          : field.name === 'document_type' ? (table === 'purchase_headers'
            ? [['ORDER', 'Đơn mua hàng'], ['QUOTE', 'Báo giá mua'], ['INVOICE', 'Hóa đơn mua'], ['RETURN_ORDER', 'Đơn trả hàng mua']]
            : [['QUOTE', 'Báo giá'], ['ORDER', 'Đơn bán']])
          : field.name === 'item_type' ? [['DEFAULT', 'Mặc định'], ['MANUFACTURED', 'Sản xuất'], ['PRODUCTION_COMPONENT', 'Linh kiện sản xuất']]
          : table === 'sales_returns' && field.name === 'status' ? (row ? [['DRAFT', 'Nháp'], ['RECEIVED', 'Đã nhận hàng — cộng tồn kho'], ['CANCELLED', 'Hủy']] : [['DRAFT', 'Nháp']])
          : table === 'production_orders' && field.name === 'status' ? (row ? [['DRAFT', 'Nháp'], ['COMPLETED', 'Hoàn tất — cập nhật tồn kho'], ['CANCELLED', 'Hủy']] : [['DRAFT', 'Nháp']])
          : field.name === 'role' ? [['EMPLOYEE', 'Employee'], ['HR', 'Human Resources'], ['LEADER', 'Team Leader'], ['MANAGER', 'Manager'], ['DIRECTOR', 'Director'], ['ADMIN', 'Administrator']] : field.type === 'boolean'
          ? [['true', 'Có'], ['false', 'Không']]
          : ['OPEN', 'RELEASED', 'PARTIALLY_SHIPPED', 'SHIPPED', 'INVOICED', 'CANCELLED'].map(s => [s, s]);
        const empty = erpElement('option', field.hasDefault && !row ? 'Dùng mặc định' : 'Chưa chọn'); empty.value = ''; input.append(empty);
        options.forEach(([key, text]) => { const option = erpElement('option', text); option.value = key; input.append(option); });
        if (relation) {
          const lookup = erpElement('input', null, 'block w-full border rounded-lg px-3 py-2');
          lookup.type = 'search';
          lookup.placeholder = productionOrderItemField
            ? 'Tìm Item theo mã hoặc tên'
            : table === 'sales_returns' && field.name === 'sales_order_id'
            ? 'Tìm mã Sales Order (SO-...)'
            : 'Tìm ' + tableLabels[relation.target_table].toLowerCase();
          lookup.setAttribute('aria-label', lookup.placeholder);
          const lookupStatus = erpElement('small', '', 'block text-slate-500');
          let requestVersion = 0;
          const fetchOptions = async () => {
            const request = ++requestVersion;
            const selected = input.value || (value == null ? '' : String(value));
            lookupStatus.textContent = 'Đang tải lựa chọn...';
            try {
              const lookupParams = {size: 100, search: lookup.value};
              if (table === 'items' && field.name === 'base_unit_of_measure') {
                lookupParams.filterColumn = 'active';
                lookupParams.filterValue = 'true';
              }
              if (table === 'sales_return_lines' && field.name === 'sales_order_line_id') {
                const parentId = controls.get('return_id')?.value;
                if (!parentId) { input.replaceChildren(empty); lookupStatus.textContent = 'Chọn phiếu trả trước.'; return; }
                const parents = await erpGet('/tables/sales_returns?' + new URLSearchParams({size: 1, filterColumn: 'id', filterValue: parentId}));
                if (!parents.rows.length) { input.replaceChildren(empty); return; }
                lookupParams.filterColumn = 'sales_order_id'; lookupParams.filterValue = parents.rows[0].sales_order_id;
              }
              if (table === 'purchase_receipts' && field.name === 'purchase_order_id') {
                lookupParams.filterColumn = 'document_type';
                lookupParams.filterValue = 'ORDER';
              }
              if (table === 'purchase_receipt_lines' && field.name === 'purchase_order_line_id') {
                const receiptId = controls.get('receipt_id')?.value;
                if (!receiptId) { input.replaceChildren(empty); lookupStatus.textContent = 'Chọn phiếu nhập trước.'; return; }
                const receipts = await erpGet('/tables/purchase_receipts?' + new URLSearchParams({
                  size: 1, filterColumn: 'id', filterValue: receiptId
                }));
                if (!receipts.rows.length || !receipts.rows[0].purchase_order_id) {
                  input.replaceChildren(empty); lookupStatus.textContent = 'Phiếu nhập độc lập, không có dòng đơn mua liên kết.'; return;
                }
                lookupParams.filterColumn = 'purchase_header_id';
                lookupParams.filterValue = receipts.rows[0].purchase_order_id;
                lookupParams.documentType = 'ORDER';
              }
              if ((table === 'production_orders' && ['item_id', 'bom_parent_item_id'].includes(field.name))
                  || (table === 'item_bom' && field.name === 'parent_item_id')) {
                lookupParams.filterColumn = 'item_type'; lookupParams.filterValue = 'MANUFACTURED';
              }
              if (table === 'item_bom' && field.name === 'component_item_id') {
                lookupParams.filterColumn = 'item_type'; lookupParams.filterValue = 'PRODUCTION_COMPONENT';
              }
              const data = await erpGet('/tables/' + relation.target_table + '?' + new URLSearchParams(lookupParams));
              if (request !== requestVersion) return;
              input.replaceChildren(empty);
              const eligibleRecords = table === 'production_orders' && field.name === 'bom_parent_item_id'
                ? data.rows.filter(record => record.item_category === 'INVENTORY' && !record.blocked)
                : table === 'production_orders' && field.name === 'item_id'
                  ? data.rows.filter(record => record.item_category === 'INVENTORY' && !record.blocked)
                  : data.rows;
              const displayName = record => relation.target_table === 'customers'
                ? record.name || record.customer_no || ''
                : relation.target_table === 'locations'
                  ? (table.startsWith('sales_') ? record.code || '' : [record.code, record.name].filter(Boolean).join(' · '))
                  : relation.target_table === 'items'
                    ? [record.item_no, record.description].filter(Boolean).join(' · ') || 'Không có mã Item'
                    : relation.target_table === 'units_of_measure'
                      ? [record.code, record.name].filter(Boolean).join(' · ') || 'Không có mã đơn vị tính'
                  : record.name || record.description || record.account_name || record.order_no || record.return_no || record.invoice_no || record.code || record.email || '';
              const showNameOnly = ['customers', 'locations', 'units_of_measure'].includes(relation.target_table);
              eligibleRecords.forEach(record => {
                const id = String(record[relation.target_column]);
                const caption = table === 'sales_returns' && field.name === 'sales_order_id'
                  ? record.order_no
                  : displayName(record);
                const option = erpElement('option',
                  relation.target_table === 'items' ? `${record.item_no || ''} · ${record.name || record.description || ''}`
                  : table === 'sales_returns' && field.name === 'sales_order_id' ? caption
                    : showNameOnly ? caption : `${id} — ${caption}`);
                option.value = id; input.append(option);
              });
              if (selected && !Array.from(input.options).some(option => option.value === selected)) {
                if (showNameOnly || relation.target_table === 'items') {
                  const selectedParams = new URLSearchParams({size: 1, filterColumn: relation.target_column, filterValue: selected});
                  const selectedData = await erpGet('/tables/' + relation.target_table + '?' + selectedParams);
                  if (request !== requestVersion) return;
                  const selectedRecord = selectedData.rows[0];
                  const option = erpElement('option', selectedRecord ? displayName(selectedRecord) :
                    relation.target_table === 'units_of_measure' ? 'Không tìm thấy đơn vị tính (' + selected + ')' : 'Không tìm thấy mã Item (ID ' + selected + ')');
                  option.value = selected; input.append(option);
                } else {
                  const option = erpElement('option', 'ID ' + selected + ' (đang chọn)'); option.value = selected; input.append(option);
                }
              }
              input.value = selected;
              form.documentRefresh?.();
              lookupStatus.textContent = data.total > 100
                ? 'Hiển thị 100 kết quả đầu. Nhập từ khóa để tìm thêm.'
                : `${eligibleRecords.length} lựa chọn`;
            } catch (error) { lookupStatus.textContent = 'Không tải được lựa chọn. Nhập từ khóa để thử lại.'; }
          };
          let timer;
          input.addEventListener('change', () => { clearTimeout(timer); requestVersion++; });
          lookup.addEventListener('input', () => { clearTimeout(timer); requestVersion++; timer = setTimeout(fetchOptions, 300); });
          if (table === 'sales_return_lines' && field.name === 'sales_order_line_id') {
            controls.get('return_id')?.addEventListener('change', () => { input.value = ''; fetchOptions(); });
          }
          if (table === 'purchase_receipt_lines' && field.name === 'purchase_order_line_id') {
            controls.get('receipt_id')?.addEventListener('change', () => { input.value = ''; fetchOptions(); });
          }
          // Preserve the selected reference even when the lookup request fails.
          if (value != null && value !== '') { const option = erpElement('option', 'ID ' + value); option.value = String(value); input.append(option); }
          label.append(lookup, lookupStatus);
          fetchOptions();
        }
      } else {
        input = erpElement(!(table === 'items' && field.name === 'description') && ['notes', 'description', 'reason', 'content', 'requirements', 'summary'].includes(field.name) ? 'textarea' : 'input', null);
        if (input.tagName === 'INPUT') {
          input.type = field.name === 'password' ? 'password' : field.name === 'email' ? 'email' :
            field.type === 'date' ? 'date' : field.type.startsWith('timestamp') ? 'datetime-local' : 'text';
          if (['numeric', 'bigint', 'integer', 'smallint'].includes(field.type)) {
            input.inputMode = field.type === 'numeric' ? 'decimal' : 'numeric';
            input.pattern = field.type === 'numeric' ? '-?[0-9]+([.][0-9]+)?' : '-?[0-9]+';
          }
          if (input.type === 'datetime-local') input.step = 'any';
          if (erpIsPriceField(field.name)) {
            erpConfigurePriceInput(input);
          }
          if (input.type === 'password') { input.autocomplete = 'new-password'; input.minLength = 8; }
        }
        if (field.maxLength) input.maxLength = field.maxLength;
      }
      input.name = field.name;
      input.className = 'block w-full border border-slate-300 rounded-lg px-3 py-2 focus:outline-indigo-500';
      input.required = !!required;
      const autoNumberField = {items:'item_no',sales_orders:'order_no',sales_headers:'quote_no',sales_invoices:'invoice_no',sales_returns:'return_no',sales_shipments:'shipment_no',purchase_returns:'return_no',purchase_receipts:'receipt_no',production_orders:'production_no'}[table];
      if (!row && field.name === autoNumberField) {
        input.required = false; input.value = ''; input.readOnly = true;
        input.placeholder = 'Tự sinh mã khi lưu';
      }
      input.value = value == null ? '' : erpIsPriceField(field.name)
        ? erpPrice(value) : String(value).replace(/^(\d{4}-\d{2}-\d{2}) /, '$1T');
      if (purchaseDocumentCreate && field.name === 'order_date' && !input.value) {
        const today = new Date();
        input.value = [today.getFullYear(), String(today.getMonth() + 1).padStart(2, '0'),
          String(today.getDate()).padStart(2, '0')].join('-');
      }
      if (purchaseDocumentCreate && field.name === 'document_type') {
        input.value = purchaseType;
        input.disabled = true;
      }
      initialValues.set(field.name, input.value);
      if (field.name === 'password' && row) input.placeholder = 'Để trống để giữ mật khẩu hiện tại';
      label.append(input); grid.append(label); controls.set(field.name, input);
      if (field.hasDefault && !row) label.append(erpElement('small', 'Để trống để dùng giá trị mặc định.', 'text-slate-500'));
    }
    if (table === 'purchase_receipt_lines' && controls.has('purchase_order_line_id') && controls.has('item_id')) {
      controls.get('purchase_order_line_id').addEventListener('change', async event => {
        const selectedId = event.currentTarget.value;
        if (!selectedId) return;
        try {
          const result = await erpGet('/tables/purchase_lines?' + new URLSearchParams({
            size: 1, filterColumn: 'id', filterValue: selectedId, documentType: 'ORDER'
          }));
          const purchaseLine = result.rows[0];
          if (!purchaseLine?.item_id) throw new Error('Dòng mua không có Item liên kết.');
          const items = await erpGet('/tables/items?' + new URLSearchParams({
            size: 1, filterColumn: 'id', filterValue: purchaseLine.item_id
          }));
          const item = items.rows[0];
          if (!item) throw new Error('Item của Purchase Line không còn tồn tại.');
          const itemSelect = controls.get('item_id');
          if (![...itemSelect.options].some(option => option.value === String(item.id))) {
            const option = erpElement('option', `${item.item_no || ''} · ${item.description || ''}`);
            option.value = String(item.id);
            itemSelect.append(option);
          }
          itemSelect.value = String(item.id);
        } catch (failure) { notifyCustomer(failure.message); }
      });
    }
    const readHeaderDetails = await appendSalesHeaderFields(form,grid,controls,table,row);
    if (version !== erpFormVersion || !erpEditDialog.open) return;
    const salesLines = !row ? salesSourceLineEditor(table, controls) : null;
    if (salesLines) grid.append(salesLines.section);
    if (table === 'items') {
      const section = erpElement('section', null, 'sm:col-span-2 border-t pt-4 space-y-2');
      section.dataset.itemInventory = 'true';
      section.append(erpElement('h3', 'Inventory', 'font-semibold'));
      const summary = erpElement('div', null, 'text-sm text-slate-700');
      const openingStockFields = erpElement('div', null, 'grid grid-cols-1 sm:grid-cols-2 gap-4');
      if (!row) {
        const locationLabel = erpElement('label', null, 'block text-sm space-y-1');
        locationLabel.append(erpElement('span', 'Kho nhập tồn ban đầu *', 'font-medium'));
        openingLocation = erpElement('select', null, 'block w-full border border-slate-300 rounded-lg px-3 py-2');
        openingLocation.required = true;
        const emptyLocation = erpElement('option', 'Đang tải danh sách kho...'); emptyLocation.value = '';
        openingLocation.append(emptyLocation);
        const locationStatus = erpElement('small', 'Cần chọn kho để ghi nhận số lượng ban đầu.', 'block text-slate-500');
        locationLabel.append(openingLocation, locationStatus);
        const quantityLabel = erpElement('label', null, 'block text-sm space-y-1');
        quantityLabel.append(erpElement('span', 'Số lượng tồn ban đầu', 'font-medium'));
        openingQuantity = erpElement('input', null, 'block w-full border border-slate-300 rounded-lg px-3 py-2');
        openingQuantity.type = 'number'; openingQuantity.min = '0'; openingQuantity.step = '0.001'; openingQuantity.value = '0'; openingQuantity.required = true;
        openingQuantity.setAttribute('aria-label', 'Số lượng tồn ban đầu');
        quantityLabel.append(openingQuantity);
        openingStockFields.append(locationLabel, quantityLabel);
        section.append(openingStockFields, erpElement('p',
          'Số lượng ban đầu được ghi nhận vào kho đã chọn và lưu lịch sử tồn kho. Các thay đổi sau đó thực hiện qua phiếu nhập, đơn bán hoặc sản xuất.',
          'text-sm text-slate-500'));
        erpGet('/tables/locations?size=100').then(data => {
          if (version !== erpFormVersion || !erpEditDialog.open) return;
          openingLocation.replaceChildren(erpElement('option', 'Chọn kho')); openingLocation.firstElementChild.value = '';
          data.rows.forEach(location => {
            const option = erpElement('option', `${location.code} · ${location.name}`);
            option.value = String(location.id); openingLocation.append(option);
          });
          locationsReady = data.rows.length > 0;
          openingLocation.disabled = !locationsReady;
          locationStatus.textContent = locationsReady ? 'Chọn kho sẽ nhận số lượng tồn ban đầu.' : 'Chưa có kho. Tạo kho trước khi tạo Item Inventory.';
        }).catch(() => {
          if (version !== erpFormVersion || !erpEditDialog.open) return;
          openingLocation.replaceChildren(erpElement('option', 'Không tải được danh sách kho'));
          openingLocation.disabled = true;
          locationStatus.textContent = 'Không tải được kho. Thử đóng và mở lại biểu mẫu.';
        });
      } else {
        section.append(summary, erpElement('p',
          'Tồn kho được cập nhật tự động theo kho khi nhập hàng, bán hàng hoặc hoàn tất sản xuất; không nhập tay tại đây.',
          'text-sm text-slate-500'));
      }
      grid.append(section);
      const refreshInventory = async () => {
        if (controls.get('item_category')?.value !== 'INVENTORY') {
          if (summary) summary.textContent = 'Không quản lý tồn kho cho loại Service hoặc Non-Inventory.';
          if (openingLocation) {
            openingLocation.disabled = true; openingLocation.required = false;
            openingQuantity.disabled = true; openingQuantity.required = false;
          }
          return;
        }
        if (openingLocation) {
          openingLocation.disabled = !locationsReady; openingLocation.required = true;
          openingQuantity.disabled = false; openingQuantity.required = true;
          return;
        }
        const unit = controls.get('base_unit_of_measure')?.value || '';
        summary.textContent = 'Đang tải tồn kho theo kho...';
        try {
          const stock = await erpGet('/tables/inventory?' + new URLSearchParams({
            size: 100, filterColumn: 'item_id', filterValue: row.id
          }));
          if (version !== erpFormVersion || !erpEditDialog.open) return;
          summary.replaceChildren(erpElement('p', `Tổng tồn: ${erpValue(row.quantity ?? '0', 'quantity')} ${unit}`.trim()));
          stock.rows.forEach(entry => summary.append(erpElement('p',
            `${entry.location_name || entry.location_id}: ${erpValue(entry.quantity, 'quantity')} ${entry.unit || unit}`.trim())));
        } catch (error) {
          summary.textContent = 'Không tải được tồn kho theo kho.';
        }
      };
      controls.get('item_category')?.addEventListener('change', refreshInventory);
      controls.get('base_unit_of_measure')?.addEventListener('input', refreshInventory);
      refreshInventory();
    }
    const error = erpElement('p', '', 'text-rose-600 text-sm'); error.id = 'erp-edit-error'; error.setAttribute('role', 'alert');
    const actions = erpElement('div', null, 'flex justify-end gap-3');
    const save = erpButton('Lưu', null, 'bg-indigo-600 text-white px-4 py-2 rounded-lg disabled:opacity-50'); save.type = 'submit';
    actions.append(erpButton('Hủy', close), save);
    let makeOrder;
    if (table === 'sales_headers' && !row) {
      makeOrder = erpButton('Make Order', null, 'bg-emerald-600 text-white px-4 py-2 rounded-lg font-semibold disabled:opacity-50');
      makeOrder.type = 'submit'; makeOrder.dataset.makeOrder = 'true'; actions.append(makeOrder);
      const quoteNumber = controls.get('quote_no');
      if (quoteNumber) { quoteNumber.required = false; quoteNumber.placeholder = 'Tự sinh Document No. khi để trống'; }
      const documentType = controls.get('document_type');
      if (documentType) {
        const updateMakeOrderVisibility = () => { makeOrder.hidden = documentType.value !== 'QUOTE'; };
        documentType.addEventListener('change', updateMakeOrderVisibility);
        updateMakeOrderVisibility();
      }
    }
    form.append(title, grid, error, actions);
    const stockHelp = {
      sales_returns: 'Tạo phiếu nháp theo đơn bán gốc. Mở phiếu → Dòng trả hàng bán để thêm hàng trả. Khi đã nhận đủ hàng, sửa trạng thái thành Đã nhận hàng để cộng tồn kho. Phiếu đã nhận không được sửa hoặc xóa. Phiếu này chưa hạch toán hoàn tiền/công nợ.',
      sales_return_lines: 'Chọn dòng thuộc đơn bán gốc; vật tư được lấy tự động. Số lượng trả phải lớn hơn 0 và không vượt số lượng còn được trả. Lưu dòng nháp chưa cộng kho.',
      items: 'Type chọn Default, Sản xuất hoặc Linh kiện sản xuất. Item loại Sản xuất được chọn trong Production Orders; BOM Lines chỉ nhận Item loại Linh kiện sản xuất. Inventory Category xác định cách theo dõi tồn kho.',
      item_bom: 'Chọn Item loại Sản xuất đã xuất hiện trong Production Orders; BOM Lines chỉ nhận Items loại Linh kiện sản xuất. Số lượng là định mức cần cho một đơn vị Item sản xuất.',
      production_orders: 'Lệnh nháp chưa đổi tồn. Khi hoàn tất, linh kiện theo BOM bị trừ và Item sản xuất được cộng vào kho đã chọn. Thiếu linh kiện sẽ không cho hoàn tất. Lệnh hoàn tất không được sửa.',
      sales_order_lines: 'Dòng đơn bán được tạo từ Sales Header. Lưu đơn bán sẽ trừ tồn kho; sửa số lượng chỉ điều chỉnh phần chênh lệch.',
      purchase_receipt_lines: 'Lưu dòng phiếu nhập sẽ cộng tồn kho. Nếu bỏ trống mã kho, dùng kho trên phiếu nhập, sau đó kho mặc định.',
      inventory: 'Số lượng tại kho sẽ đồng bộ vào tổng quantity của Item. Dùng phiếu nhập cho hàng mua, lệnh sản xuất cho Item có production.'
    };
    if (stockHelp[table]) grid.before(erpElement('p', stockHelp[table], 'text-sm text-indigo-700 bg-indigo-50 rounded-lg p-3'));
    if (table === 'users') {
      const help = erpElement('p', 'Admin và Giám đốc luôn được xem Dashboard. Bật “Cấp quyền xem Dashboard” để cấp riêng cho Manager, Leader hoặc Nhân viên. Chỉ Admin quản lý tài khoản và phân quyền.', 'text-sm text-indigo-700 bg-indigo-50 rounded-lg p-3');
      grid.before(help);
    }
    let readLines;
    form.onsubmit = async event => {
      event.preventDefault();
      if (erpSaving) return;
      const convertAfterSave = event.submitter?.dataset.makeOrder === 'true';
      if (table === 'payments') {
        if (!controls.get('customer_id')?.value && !controls.get('supplier_id')?.value) {
          error.textContent = 'Chọn khách hàng hoặc nhà cung cấp cho phiếu thanh toán.'; return;
        }
        const amount = controls.get('amount')?.value.trim() || '';
        if (!/^\d+(\.\d+)?$/.test(amount) || !/[1-9]/.test(amount)) {
          error.textContent = 'Số tiền thanh toán phải lớn hơn 0.'; controls.get('amount')?.focus(); return;
        }
      }
      let selectedSalesLines;
      if (salesLines) {
        try { selectedSalesLines = salesLines.read(); }
        catch (failure) { error.textContent = failure.message; return; }
      }
      const payload = {};
      let invalidPriceInput = null;
      fields.forEach(field => {
        if (!field.writable) return;
        const input = controls.get(field.name), value = field.name === 'password' ? input.value : input.value.trim();
        if (row && value === initialValues.get(field.name)) return;
        if (!row && value === '' && field.hasDefault) return;
        if (row && field.name === 'password' && !value) return;
        if (erpIsPriceField(field.name) && value !== '') {
          try { payload[field.name] = erpNormalizePrice(value); }
          catch (failure) {
            invalidPriceInput = input;
            error.textContent = `${field.label || field.name}: ${failure.message}`;
            return;
          }
        } else {
          payload[field.name] = value === '' ? null : field.type === 'boolean' ? value === 'true' : value;
        }
      });
      if (invalidPriceInput) { invalidPriceInput.focus(); return; }
      if (table === 'items' && !row && controls.get('item_category')?.value === 'INVENTORY') {
        if (!locationsReady || !openingLocation.value) {
          error.textContent = locationsReady ? 'Chọn kho nhận tồn ban đầu.' : 'Không có kho khả dụng. Tạo kho trước khi tạo Item Inventory.';
          openingLocation.focus(); return;
        }
        if (!/^\d+(\.\d{1,3})?$/.test(openingQuantity.value) || Number(openingQuantity.value) < 0) {
          error.textContent = 'Nhập tồn ban đầu từ 0 trở lên, tối đa 3 chữ số thập phân.';
          openingQuantity.focus(); return;
        }
        payload.initial_location_id = openingLocation.value;
        payload.initial_quantity = openingQuantity.value;
      }
      const headerDetails=readHeaderDetails();
      if(Object.keys(headerDetails).length)payload.document_details=headerDetails;
      if (row && table !== 'purchase_headers' && !Object.keys(payload).length) { error.textContent = 'Chưa có thay đổi để lưu.'; return; }
      erpSaving = true; save.disabled = true; save.textContent = 'Đang lưu...'; error.textContent = '';
      if (makeOrder) makeOrder.disabled = true;
      try {
        let savedDocument;
        if (salesDocumentCreate) savedDocument = await saveSalesDocument(payload, readLines(), salesDocumentType === 'QUOTE');
        else if (table === 'purchase_headers') savedDocument = await savePurchaseDocument({...row, ...payload}, purchaseLines(), row?.id);
        else if (salesLines) {
          const endpoint = table === 'sales_invoices' ? '/sales-invoices/with-lines' : '/sales-returns/with-lines';
          const response = await fetch(erpApi + endpoint, {
            method: 'POST', headers: {Accept: 'application/json', 'Content-Type': 'application/json'},
            body: JSON.stringify({header: payload, lines: selectedSalesLines}), signal: AbortSignal.timeout(20000)
          });
          const result = await response.json().catch(() => ({}));
          if (!response.ok) throw new Error(result.message || `Không lưu được chứng từ và Sales Lines (HTTP ${response.status}).`);
          savedDocument = result;
        }
        else await erpWrite(table, row?.id, row ? 'PUT' : 'POST', payload);
        erpEditDialog.close(); notifyCustomer('Đã lưu ' + tableLabels[table].toLowerCase() + '.');
        erpCatalog = null;
        if (afterSave) {
          await ensureErpCatalog();
          await afterSave();
        } else await renderErpData();
        if (table === 'sales_headers' && savedDocument?.id) {
          const saved = await erpGet('/tables/sales_headers?filterColumn=id&filterValue=' + encodeURIComponent(savedDocument.id));
          if (saved.rows.length) {
            showErpDetail('sales_headers', saved.rows[0], saved.columns);
            if (convertAfterSave) {
              const action = document.querySelector('#erp-detail-dialog .profile-actions button');
              await makeSalesQuoteOrder(saved.rows[0], action || {disabled: false});
            }
          }
        }
        if (table === 'purchase_headers' && savedDocument?.id) {
          const saved = await erpGet('/tables/purchase_headers?filterColumn=id&filterValue=' + encodeURIComponent(savedDocument.id));
          if (saved.rows.length) showErpDetail('purchase_headers', saved.rows[0], saved.columns);
        }
        if (table === 'customers') await loadCustomersFromDatabase();
      } catch (failure) { error.textContent = failure.message; }
      finally { erpSaving = false; save.disabled = false; save.textContent = 'Lưu'; if (makeOrder) makeOrder.disabled = false; }
    };
    erpEditDialog.replaceChildren(form);
    if (table === 'items') form.dataset.itemCard = 'true';
    if (['sales_headers', 'sales_orders', 'sales_invoices', 'sales_returns'].includes(table)) form.dataset.salesDocument = 'true';
    if (table === 'purchase_headers') form.dataset.purchaseDocument = 'true';
    enhanceDocumentForm(erpEditDialog);
    if (table === 'items') arrangeItemCard(form, row);
    if (salesDocumentCreate) readLines = salesDocumentLines(form, salesDocumentType === 'QUOTE', controls.get('location_id'));
    const purchaseLines = table === 'purchase_headers' ? purchaseDocumentLines(form, controls, purchaseType, row) : null;
    if(row && readHeaderDetails.headerId)await appendSavedSalesLines(form,readHeaderDetails.headerId);
    if(version!==erpFormVersion || !erpEditDialog.open)return;
    if(form.dataset.salesDocument==='true'){arrangeSalesForm(form);scopeSalesFields(form,salesDocumentType);}
    if(form.dataset.purchaseDocument==='true')arrangePurchaseForm(form,row,purchaseType);
    if(row&&readHeaderDetails.headerId)appendSalesDocumentActions(form,table,row,readHeaderDetails.headerId);
    mountDocumentHeader(form.querySelector('.document-actions'), {table, row});
    const invoiceDetails = form.querySelector('[data-invoice-details]');
    if (invoiceDetails) {
      const linesSection = form.querySelector('.sales-document') || salesLines?.section;
      if (linesSection) linesSection.after(invoiceDetails);
    }
    grid.querySelector('input, select, textarea')?.focus();
  } catch (error) {
    erpEditDialog.replaceChildren(erpElement('p', 'Không tải được biểu mẫu. Kiểm tra backend.', 'text-rose-600'), erpButton('Đóng', close));
  }
}

async function deleteErpRow(table, row, button, afterDelete = null) {
  if (!canDeleteRecords()) return;
  if (button.disabled) return;
  const question = table === 'inventory'
    ? 'Xóa Inventory #' + row.id + '? Admin sẽ giải phóng toàn bộ số lượng giữ chỗ hiện tại, điều chỉnh tồn về 0 và lưu lịch sử trước khi xóa. Thao tác không thể hoàn tác.'
    : `Xóa ${tableLabels[table].toLowerCase()} #${row.id}? Thao tác này không thể hoàn tác.`;
  if (!await confirmAction(question)) return;
  button.disabled = true;
  try {
    await erpWrite(table, row.id, 'DELETE', undefined, table === 'inventory');
    notifyCustomer('Đã xóa bản ghi.'); erpCatalog = null;
    if (afterDelete) await afterDelete();
    else {
      document.getElementById('erp-detail-dialog').close();
      if (erpState.page > 0) erpState.page--;
      await renderErpData();
    }
    if (table === 'customers') await loadCustomersFromDatabase();
  } catch (error) { notifyCustomer(error.message); }
  finally { button.disabled = false; }
}
