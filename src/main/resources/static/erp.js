const erpApi = isLocalPreview ? 'http://localhost:8081/api/erp' : '/api/erp';
const erpGroups = {
  items: ['Items & Inventory', ['items', 'inventory', 'inventory_ledger']],
  production: ['Production', ['item_bom', 'production_orders', 'production_components']],
  salesorders: ['Sales', ['sales_document_lines', 'sales_headers', 'sales_shipments', 'sales_shipment_lines', 'sales_invoices', 'sales_invoice_lines', 'sales_returns', 'sales_return_lines']],
  categories: ['Đơn vị tính & Thuế', ['units_of_measure', 'tax_codes']],
  locations: ['Locations', ['locations']],
  purchases: ['Purchasing', ['purchase_headers', 'purchase_lines', 'purchase_receipts', 'purchase_receipt_lines', 'purchase_returns']],
  suppliers: ['Suppliers', ['vendors', 'supplier', 'supplier_ledger']],
  finance: ['Payments & Accounting', ['payments', 'payment_allocations', 'accounts', 'general_ledger', 'customer_ledger', 'tax_entries']],
  people: ['Employees & Users', ['employees', 'users']],
  database: ['All Data', []]
};
const tableLabels = {
  sales_setup_codes: 'Sales Setup · Danh mục lookup',
  sales_headers: 'Sales',
  sales_document_lines: 'Sales Lines',
  sales_returns: 'Sales Returns · Trả hàng bán', sales_return_lines: 'Dòng trả hàng bán',
  item_bom: 'Định mức BOM', production_orders: 'Lệnh sản xuất', production_components: 'Linh kiện đã tiêu hao',
  accounts: 'Chart of Accounts', customer_ledger: 'Customer Ledger',
  customers: 'Customers', employees: 'Employees', general_ledger: 'General Ledger', inventory: 'Inventory',
  inventory_ledger: 'Inventory Ledger', items: 'Mặt hàng (Items)', locations: 'Locations', payment_allocations: 'Payment Allocations',
  payments: 'Payments', purchase_headers: 'Purchase Documents · Chứng từ mua',
  purchase_lines: 'Purchase Lines · Dòng chứng từ mua',
  purchase_receipt_lines: 'Purchase Receipt Lines', purchase_receipts: 'Purchase Receipts', purchase_returns: 'Purchase Returns',
  sales_invoice_lines: 'Sales Invoice Lines', sales_invoices: 'Sales Invoices', sales_lines: 'Sales Lines',
  sales_order_lines: 'Sales Order Lines', sales_quote_lines: 'Sales Quote Lines', sales_orders: 'Sales Orders', sales_shipment_lines: 'Sales Shipment Lines',
  sales_shipments: 'Sales Shipments', supplier: 'Suppliers', supplier_ledger: 'Supplier Ledger',
  tax_codes: 'Tax Codes', tax_entries: 'Tax Entries', units_of_measure: 'Units of Measure', users: 'Users', vendors: 'Vendors'
};
const salesDocumentTitles = {
  QUOTE: 'Sales Quote',
  ORDER: 'Sales Order',
  INVOICE: 'Sales Invoice',
  RETURN_ORDER: 'Sales Return Order'
};
const hiddenSalesStorageTables = new Set(['sales_orders', 'sales_order_lines', 'sales_lines']);
const salesLineTables = new Set(['sales_document_lines', 'sales_quote_lines', 'sales_order_lines', 'sales_invoice_lines', 'sales_return_lines']);
const hiddenSalesLineMetadata = new Set(['source_table', 'source_line_id']);
const columnLabels = {
  quote_no: 'No.', document_type: 'Loại chứng từ', document_no: 'Document No.', sales_return_id: 'Sales Return Order', sales_invoice_id: 'Sales Invoice',
  return_no: 'Mã phiếu trả', return_id: 'Phiếu trả hàng', return_date: 'Ngày trả hàng', received_at: 'Ngày nhận hàng trả', sales_order_line_id: 'Dòng đơn bán gốc',
  item_code: 'Mã vật tư', item_name: 'Tên vật tư', location_name: 'Tên kho', average_cost: 'Giá vốn bình quân',
  item_category: 'Type', item_type: 'Origin', parent_item_id: 'Item sản xuất', component_item_id: 'Linh kiện',
  production_no: 'Mã lệnh sản xuất', production_order_id: 'Lệnh sản xuất',
  quantity_per_unit: 'Định mức mỗi đơn vị', consumed_quantity: 'Số lượng đã tiêu hao', completed_at: 'Hoàn tất lúc',
  id: 'ID', username: 'Tên đăng nhập', role: 'Vai trò', dashboard_access: 'Cấp quyền xem Dashboard', name: 'Tên', code: 'Mã', description: 'Mô tả', address: 'Địa chỉ', phone: 'Điện thoại', email: 'Email',
  customer_no: 'Mã khách hàng bán', sell_to_name: 'Tên khách hàng bán', customer_id: 'Khách hàng', item_no: 'Mã vật tư', item_id: 'Vật tư',
  product_id: 'Sản phẩm', category_id: 'Danh mục', location_id: 'Kho', order_no: 'Document No. (Sales Order)', order_date: 'Ngày đặt',
  posting_date: 'Posting Date', created_at: 'Ngày tạo', updated_at: 'Ngày cập nhật', status: 'Trạng thái',
  requested_delivery_date: 'Ngày giao hàng yêu cầu', salesperson_code: 'Mã nhân viên bán hàng', subtotal: 'Tổng trước VAT',
  blocked: 'Tạm khóa', active: 'Hoạt động', quantity: 'Tồn kho (Inventory)', unit_price: 'Đơn giá', unit_cost: 'Giá vốn',
  total_amount: 'Tổng tiền', vat_amount: 'Tiền thuế', discount_amount: 'Tiền giảm giá', currency_code: 'Tiền tệ',
  payment_terms_code: 'Điều khoản thanh toán', payment_method_code: 'Phương thức thanh toán',
  debit: 'Nợ', credit: 'Có', balance: 'Số dư', amount: 'Số tiền', invoice_no: 'Mã hóa đơn',
  vendor_id: 'Nhà cung cấp', supplier_id: 'Nhà cung cấp', sales_order_id: 'Đơn bán', related_sales_order_id: 'Đơn bán gốc', purchase_order_id: 'Đơn mua',
  available_quantity: 'Số lượng có thể xuất', reserved_quantity: 'Số lượng giữ cho đơn hàng', notes: 'Ghi chú',
  password: 'Mật khẩu', employee_code: 'Mã nhân viên', department: 'Phòng ban', position: 'Chức vụ',
  hire_date: 'Ngày vào làm', user_id: 'Người dùng', assigned_user_id: 'ID người được giao', account_no: 'Số tài khoản', account_name: 'Tên tài khoản',
  account_type: 'Loại tài khoản', parent_id: 'Mục cha', payment_no: 'Mã thanh toán', payment_type: 'Loại thanh toán',
  payment_date: 'Ngày thanh toán', payment_method: 'Phương thức thanh toán', account_id: 'Tài khoản kế toán',
  purchase_price: 'Giá mua', sale_price: 'Giá bán', vat_rate: 'Thuế suất', unit: 'Đơn vị',
  line_no: 'Số dòng', unit_of_measure: 'Đơn vị tính', base_unit_of_measure: 'Đơn vị tính cơ bản',
  discount_percent: 'Giảm giá (%)', vat_percent: 'Thuế (%)', line_amount: 'Thành tiền',
  sales_header_id: 'Đơn bán', vendor_no: 'Mã nhà cung cấp', reason: 'Lý do', rate: 'Thuế suất',
  document_no: 'Số chứng từ', document_date: 'Ngày chứng từ', customer_no: 'Mã khách hàng',
  discount_amount: 'Tiền giảm giá', location_name: 'Tên kho', related_document_no: 'Chứng từ liên quan',
  line_status: 'Trạng thái dòng'
};
Object.assign(columnLabels, {
  purchase_header_id: 'Purchase Header', source_purchase_line_id: 'Dòng đơn mua gốc', receipt_line_id: 'Dòng phiếu nhập',
  purchase_receipt_line_id: 'Dòng phiếu nhập', vendor_reference: 'Mã tham chiếu nhà cung cấp',
  vendor_name: 'Nhà cung cấp', vendor_no: 'Mã nhà cung cấp',
  external_document_no: 'Số chứng từ ngoài', requested_receipt_date: 'Ngày nhận hàng yêu cầu',
  promised_receipt_date: 'Ngày nhận hàng dự kiến', shipment_method_code: 'Phương thức giao hàng',
  quantity_received: 'Số lượng đã nhận', quantity_invoiced: 'Số lượng đã lập hóa đơn',
  discount_percent: 'Giảm giá (%)', location_code: 'Mã kho'
});
let erpCatalog = null;
const erpReadOnlyTables = new Set(['inventory_ledger', 'production_components', 'sales_lines', 'sales_orders', 'sales_headers', 'sales_document_lines', 'purchase_headers', 'purchase_lines']);
let erpCatalogPromise = null;
let erpState = {group: 'database', table: 'customers', page: 0, size: 25, search: '', filterColumn: null, filterValue: null, documentType: null};
let erpRequestVersion = 0;

function erpElement(tag, text, classes = '') {
  const element = document.createElement(tag);
  if (text !== null) element.textContent = text;
  element.className = classes;
  return element;
}
function erpButton(text, action, classes = 'px-3 py-2 border rounded-lg text-sm hover:bg-indigo-50') {
  const button = erpElement('button', text, classes);
  button.type = 'button';
  button.onclick = action;
  if (text && text.startsWith('Xóa')) button.classList.add('delete-only');
  return button;
}
function salesLineDeleteButton(text, action, classes) {
  const button = erpButton(text, action, classes);
  if (!canDeleteRecords()) {
    button.classList.remove('delete-only');
    button.disabled = true;
    button.title = 'Chỉ Administrator hoặc Director mới có quyền xóa Sales Lines.';
  }
  return button;
}
function renderErpNavigation(catalog) {
  const nav = document.getElementById('erp-navigation');
  const classes = 'nav-btn w-full text-left px-3 py-2 rounded-lg text-slate-300 hover:bg-slate-800 text-sm';
  const dashboard = erpButton('Dashboard', () => switchTab('dashboard'), classes);
  dashboard.id = 'nav-dashboard';
  const all = erpButton('All Data', () => openErpGroup('database'), classes);
  all.id = 'nav-database';
  nav.replaceChildren(...(canViewDashboard() ? [dashboard, all] : [all]));
  const hr = erpElement('details', null, 'pt-1');
  hr.append(erpElement('summary', 'Human Resources', 'px-3 py-2 text-xs font-semibold text-slate-300 cursor-pointer rounded-lg hover:bg-slate-800'));
  [['leaves', 'Leave Requests'], ['tasks', 'Tasks'], ['payroll', 'Payroll'], ['posts', 'Recruitment & News & Announcements']].forEach(([key, label]) => {
    const button = erpButton(label, () => openHr(key), classes);
    button.id = 'nav-hr-' + key;
    hr.append(button);
  });
  const groups = [['Customers', ['customers']], ...Object.entries(erpGroups)
    .filter(([key]) => !['database', 'salesorders', 'purchases'].includes(key))
    .map(([, group]) => group)];
  const available = new Map(catalog.tables.map(table => [table.name, table]));
  const rendered = new Set();
  const otherSections = [];
  const appendGroup = (title, names) => {
    const section = erpElement('details', null, 'pt-1');
    section.dataset.tableGroup = title;
    section.append(erpElement('summary', title, 'px-3 py-2 text-xs font-semibold text-slate-300 cursor-pointer rounded-lg hover:bg-slate-800'));
    names.forEach(name => {
      if (hiddenSalesStorageTables.has(name)) { rendered.add(name); return; }
      if (!available.has(name) || rendered.has(name)) return;
      rendered.add(name);
      const button = erpButton('', () => name === 'customers' ? switchTab('customers') : openErpTable(name), classes);
      button.id = name === 'customers' ? 'nav-customers' : 'nav-table-' + name;
      button.dataset.table = name;
      button.title = name;
      const line = erpElement('span', null, 'flex justify-between gap-2 items-start');
      line.append(erpElement('span', tableLabels[name] || name),
        erpElement('span', String(available.get(name).count), 'text-xs text-slate-400 shrink-0'));
      button.append(line);
      section.append(button);
    });
    if (section.querySelector('[data-table]')) otherSections.push(section);
  };
  groups.forEach(([title, names]) => appendGroup(title, names));
  if (available.has('sales_headers')) {
    const sales = erpElement('details', null, 'pt-1');
    sales.dataset.tableGroup = 'Sales';
    sales.append(erpElement('summary', 'Sales', 'px-3 py-2 text-xs font-semibold text-slate-300 cursor-pointer rounded-lg hover:bg-slate-800'));
    const linesButton = erpButton('', () => openErpTable('sales_document_lines'), classes);
    linesButton.id = 'nav-sales-lines';
    linesButton.dataset.table = 'sales_document_lines';
    linesButton.append(erpElement('span', tableLabels.sales_document_lines));
    sales.append(linesButton);
    rendered.add('sales_document_lines');
    [
      ['RETURN_ORDER', 'Sales Return Order'],
      ['ORDER', 'Sales Order'],
      ['QUOTE', 'Sales Quotes'],
      ['INVOICE', 'Sales Invoice']
    ].forEach(([type, label]) => {
      const button = erpButton('', () => openSalesDocuments(type), classes);
      button.id = 'nav-salesdoc-' + type.toLowerCase();
      button.dataset.table = 'sales_headers';
      button.dataset.documentType = type;
      const line = erpElement('span', null, 'flex justify-between gap-2 items-start');
      line.append(erpElement('span', label));
      button.append(line);
      sales.append(button);
    });
    rendered.add('sales_headers');
    nav.append(sales);
  }
  if (available.has('purchase_headers')) {
    const purchases = erpElement('details', null, 'pt-1');
    purchases.dataset.tableGroup = 'Purchasing';
    purchases.append(erpElement('summary', 'Purchasing', 'px-3 py-2 text-xs font-semibold text-slate-300 cursor-pointer rounded-lg hover:bg-slate-800'));
    const lines = erpButton('', () => openErpTable('purchase_lines'), classes);
    lines.dataset.table = 'purchase_lines';
    lines.append(erpElement('span', tableLabels.purchase_lines));
    purchases.append(lines);
    [
      ['ORDER', 'Purchase Order'],
      ['QUOTE', 'Purchase Quotes'],
      ['INVOICE', 'Purchase Invoice']
    ].forEach(([type, label]) => {
      const button = erpButton('', () => openPurchaseDocuments(type), classes);
      button.dataset.table = 'purchase_headers';
      button.dataset.documentType = type;
      button.append(erpElement('span', label));
      purchases.append(button);
    });
    rendered.add('purchase_headers');
    rendered.add('purchase_lines');
    nav.append(purchases);
  }
  nav.append(hr, ...otherSections);
  document.getElementById('erp-table-count').textContent = `${rendered.size} danh mục quản lý`;
  filterErpNavigation();
  syncErpNavigation();
}
function filterErpNavigation() {
  const query = document.getElementById('erp-table-search').value.trim().toLowerCase();
  document.querySelectorAll('#erp-navigation [data-table-group]').forEach(section => {
    let matches = 0;
    section.querySelectorAll('[data-table]').forEach(button => {
      const show = (button.textContent + ' ' + button.dataset.table).toLowerCase().includes(query);
      button.hidden = !show;
      if (show) matches++;
    });
    section.hidden = matches === 0;
    if (query) {
      if (!section.hasAttribute('data-before-search')) section.dataset.beforeSearch = String(section.open);
      section.open = matches > 0;
    } else if (section.hasAttribute('data-before-search')) {
      section.open = section.dataset.beforeSearch === 'true';
      delete section.dataset.beforeSearch;
    }
  });
}
function syncErpNavigation() {
  if (!document.getElementById('tab-hr').classList.contains('hidden')) return;
  const customersVisible = !document.getElementById('tab-customers').classList.contains('hidden');
  const dashboardVisible = !document.getElementById('tab-dashboard').classList.contains('hidden');
  const selected = dashboardVisible ? 'nav-dashboard' : customersVisible || erpState.table === 'customers'
    ? 'nav-customers' : erpState.table === 'sales_headers' && (erpState.filterColumn === 'document_type' || erpState.documentType)
      ? 'nav-salesdoc-' + String(erpState.documentType || erpState.filterValue).toLowerCase()
      : erpState.table === 'sales_document_lines' ? 'nav-sales-lines'
      : 'nav-table-' + erpState.table;
  document.querySelectorAll('#erp-navigation .nav-btn').forEach(button => {
    button.classList.toggle('active', button.id === selected);
    if (button.id === selected) {
      button.setAttribute('aria-current', 'page');
      const group = button.closest('details');
      if (group) group.open = true;
    }
    else button.removeAttribute('aria-current');
  });
}
document.getElementById('erp-table-search').addEventListener('input', filterErpNavigation);
async function erpGet(path) {
  const response = await fetch(erpApi + path, {headers: {Accept: 'application/json'}, signal: AbortSignal.timeout(20000)});
  if (!response.ok) throw new Error(`Không tải được dữ liệu (HTTP ${response.status}).`);
  return response.json();
}
async function ensureErpCatalog(refresh = false) {
  if (refresh) erpCatalog = null;
  if (erpCatalog) return erpCatalog;
  if (!erpCatalogPromise) erpCatalogPromise = erpGet('/catalog').then(data => {
    erpCatalog = data;
    erpGroups.database[1] = data.tables.map(table => table.name)
      .filter(name => !hiddenSalesStorageTables.has(name));
    renderErpNavigation(data);
    const counts = Object.fromEntries(data.tables.map(table => [table.name, table.count]));
    document.getElementById('erp-order-count').textContent = (counts.sales_headers ?? 0).toLocaleString('vi-VN');
    document.getElementById('erp-invoice-count').textContent = (counts.sales_invoices ?? 0).toLocaleString('vi-VN');
    return data;
  }).finally(() => { erpCatalogPromise = null; });
  return erpCatalogPromise;
}

async function renderErpDashboard(refresh = false) {
  if (!canViewDashboard()) return;
  const panel = document.getElementById('tab-dashboard');
  panel.replaceChildren(erpElement('p', 'Đang tải tổng quan...'));
  try {
    const catalog = await erpGet('/dashboard');
    panel.replaceChildren(erpElement('h2', 'Tổng quan dữ liệu ERP', 'text-xl font-bold'),
      erpElement('p', 'Số bản ghi hiện có trong từng danh mục. Chọn một mục để xem dữ liệu.', 'text-sm text-slate-500'),
      erpElement('p', 'Các bản ghi có mã DEMO- hoặc nhãn [DEMO] là dữ liệu minh họa, không phải giao dịch thực tế.', 'text-sm bg-amber-50 text-amber-800 border border-amber-200 rounded-lg p-3'),
      erpButton('Làm mới số liệu', () => renderErpDashboard(true)));
    const cards = erpElement('div', null, 'grid grid-cols-2 xl:grid-cols-4 gap-4');
    catalog.tables.filter(table => !hiddenSalesStorageTables.has(table.name)).forEach(table => {
      const card = erpButton('', () => openErpTable(table.name), 'bg-white border rounded-xl p-4 text-left hover:border-indigo-500');
      card.append(erpElement('p', tableLabels[table.name], 'text-sm text-slate-600'),
        erpElement('strong', table.count.toLocaleString('vi-VN'), 'block text-2xl mt-2'),
        erpElement('small', table.name, 'text-slate-400'));
      cards.append(card);
    });
    panel.append(cards);
  } catch (error) {
    panel.replaceChildren(erpElement('p', 'Không tải được tổng quan. Kiểm tra backend.', 'text-rose-600'), erpButton('Thử lại', () => renderErpDashboard(true)));
  }
}

async function openErpGroup(group) {
  erpState = {...erpState, group, table: erpGroups[group][1][0] || 'customers', page: 0, search: '', filterColumn: null, filterValue: null, documentType: null};
  switchTab('data');
  document.getElementById('nav-' + group)?.classList.add('active');
  await renderErpData();
}
async function openPurchaseDocuments(documentType) {
  const label = {ORDER: 'Purchase Orders', QUOTE: 'Purchase Quotes', INVOICE: 'Purchase Invoices'}[documentType];
  if (!label) throw new Error('Loại chứng từ mua không hợp lệ.');
  await openErpTable('purchase_headers', 'document_type', documentType);
  const heading = document.getElementById('tab-data').querySelector('h2');
  if (heading) heading.textContent = label;
}
async function openErpTable(table, filterColumn = null, filterValue = null) {
  let documentType = null;
  if (['sales_orders', 'sales_returns', 'sales_invoices'].includes(table)) {
    documentType = {sales_orders: 'ORDER', sales_returns: 'RETURN_ORDER', sales_invoices: 'INVOICE'}[table];
    if (filterColumn === 'sales_order_id' && table !== 'sales_orders') filterColumn = 'related_sales_order_id';
    table = 'sales_headers';
    if (filterColumn === 'id') filterColumn = 'sales_order_id';
    if (!filterColumn) {
      filterColumn = 'document_type';
      filterValue = documentType;
      documentType = null;
    }
  }
  erpState = {...erpState, group: ['purchase_headers', 'purchase_lines'].includes(table) ? 'purchases' : filterColumn === 'document_type' || table === 'sales_document_lines' ? 'salesorders' : 'database',
    table, page: 0, search: '', filterColumn, filterValue, documentType};
  document.getElementById('erp-detail-dialog').close();
  switchTab('data');
  document.getElementById('nav-database').classList.add('active');
  await renderErpData();
}

async function openSalesDocuments(documentType) {
  const label = {
    RETURN_ORDER: 'Sales Return Order',
    ORDER: 'Sales Order',
    QUOTE: 'Sales Quotes',
    INVOICE: 'Sales Invoice'
  }[documentType];
  if (!label) throw new Error('Loại chứng từ bán không hợp lệ.');
  await openErpTable('sales_headers', 'document_type', documentType);
  const panel = document.getElementById('tab-data');
  const heading = panel.querySelector('h2');
  if (heading) heading.textContent = label;
}

async function renderErpData() {
  const version = ++erpRequestVersion;
  const state = {...erpState};
  const panel = document.getElementById('tab-data');
  panel.replaceChildren(erpElement('p', 'Đang tải dữ liệu...'));
  try {
    await ensureErpCatalog();
    const params = new URLSearchParams({page: state.page, size: state.size, search: state.search});
    if (state.filterColumn) { params.set('filterColumn', state.filterColumn); params.set('filterValue', state.filterValue); }
    if (state.documentType) params.set('documentType', state.documentType);
    const data = await erpGet('/tables/' + encodeURIComponent(state.table) + '?' + params);
    if (version !== erpRequestVersion) return;
    syncErpNavigation();
    panel.replaceChildren(erpElement('h2', tableLabels[state.table], 'text-xl font-bold'));
    const toolbar = erpElement('div', null, 'flex flex-wrap gap-3 items-center');
    const select = erpElement('select', null, 'border rounded-lg p-2 bg-white max-w-full');
    select.setAttribute('aria-label', 'Chọn bảng dữ liệu');
    erpGroups[state.group][1].filter(name => !hiddenSalesStorageTables.has(name)
      && erpCatalog.tables.some(table => table.name === name)).forEach(name => {
      const option = erpElement('option', tableLabels[name] || name);
      option.value = name;
      option.selected = name === state.table;
      select.append(option);
    });
    select.onchange = () => { erpState.table = select.value; erpState.page = 0; erpState.search = ''; erpState.filterColumn = null; erpState.filterValue = null; erpState.documentType = null; renderErpData(); };
    const searchForm = erpElement('form', null, 'flex gap-2');
    const search = erpElement('input', null, 'border rounded-lg px-3 py-2');
    search.placeholder = 'Tìm trong tất cả trường...';
    search.setAttribute('aria-label', 'Tìm trong bảng');
    search.maxLength = 200;
    search.value = state.search;
    const find = erpButton('Tìm kiếm', null);
    find.type = 'submit';
    searchForm.append(search, find);
    searchForm.onsubmit = event => { event.preventDefault(); erpState.search = search.value.trim(); erpState.page = 0; renderErpData(); };
    toolbar.append(select, searchForm, erpButton('Làm mới', () => { erpCatalog = null; renderErpData(); }));
    let selectedQuote = null;
    let convertingQuote = false;
    const quoteRows = [];
    const makeOrder = erpButton('Make Order', async () => {
      if (!selectedQuote || convertingQuote) return;
      convertingQuote = true;
      try { await makeSalesQuoteOrder(selectedQuote, makeOrder); }
      finally { convertingQuote = false; }
    }, 'px-4 py-2 bg-indigo-600 text-white rounded-lg font-semibold disabled:opacity-50');
    makeOrder.disabled = true;
    const selectedSalesLines = new Map();
    const bulkDeleteLines = salesLineDeleteButton('Xóa dòng đã chọn (0)', async event => {
      if (!canDeleteRecords() || !selectedSalesLines.size) return;
      const selected = [...selectedSalesLines.values()];
      if (!await confirmAction(`Xóa ${selected.length} Sales Lines đã chọn? Thao tác này không thể hoàn tác.`)) return;
      event.currentTarget.disabled = true;
      let deleted = 0;
      let failure = null;
      for (const line of selected) {
        try { await erpWrite(line.table, line.id, 'DELETE'); deleted++; }
        catch (error) { failure = {line, error}; break; }
      }
      selectedSalesLines.clear();
      erpCatalog = null;
      await renderErpData();
      if (failure) notifyCustomer(`Đã xóa ${deleted}/${selected.length} dòng. Dòng #${failure.line.id} không xóa được: ${failure.error.message}`);
      else notifyCustomer(`Đã xóa ${deleted} Sales Lines.`);
    }, 'px-3 py-2 bg-rose-600 text-white rounded-lg text-sm disabled:opacity-50');
    bulkDeleteLines.disabled = true;
    const purchaseSelected = new Map();
    const purchaseChecks = [];
    const purchaseSelectAll = erpElement('input'); purchaseSelectAll.type = 'checkbox';
    purchaseSelectAll.setAttribute('aria-label', 'Chọn tất cả chứng từ nháp trên trang');
    const deletePurchases = erpButton('Xóa đã chọn (0)', async event => {
      if (!canDeleteRecords() || !purchaseSelected.size) return;
      const chosen = [...purchaseSelected.values()];
      if (!await confirmAction('Xóa ' + chosen.length + ' chứng từ mua đã chọn và các dòng hàng thuộc chứng từ?')) return;
      event.currentTarget.disabled = true;
      let deleted = 0, failure = '';
      for (const document of chosen) {
        try { await erpWrite('purchase_headers', document.id, 'DELETE'); deleted++; }
        catch (error) { failure = document.document_no + ': ' + error.message; break; }
      }
      erpCatalog = null;
      await renderErpData();
      notifyCustomer('Đã xóa ' + deleted + '/' + chosen.length + ' chứng từ.' + (failure ? ' ' + failure : ''));
    });
    deletePurchases.disabled = true;
    const syncPurchases = () => {
      deletePurchases.textContent = 'Xóa đã chọn (' + purchaseSelected.size + ')';
      deletePurchases.disabled = !canDeleteRecords() || !purchaseSelected.size;
      const enabled = purchaseChecks.filter(entry => !entry.input.disabled);
      purchaseSelectAll.disabled = !enabled.length;
      purchaseSelectAll.checked = enabled.length > 0 && enabled.every(entry => entry.input.checked);
      purchaseSelectAll.indeterminate = enabled.some(entry => entry.input.checked) && !purchaseSelectAll.checked;
    };
    purchaseSelectAll.onchange = () => {
      purchaseChecks.forEach(({input, row}) => {
        if (input.disabled) return;
        input.checked = purchaseSelectAll.checked;
        if (input.checked) purchaseSelected.set(String(row.id), row); else purchaseSelected.delete(String(row.id));
      });
      syncPurchases();
    };
    if (state.table === 'purchase_headers') toolbar.append(deletePurchases);
    const selectionHint = erpElement('span', 'Chọn báo giá nháp để tạo Sales Order.', 'text-sm text-slate-500');
    selectionHint.setAttribute('role', 'status');
    const salesType = state.filterColumn === 'document_type' ? state.filterValue : state.documentType;
    const isSalesQuoteList = state.table === 'sales_headers' && salesType === 'QUOTE';
    const salesQuoteColumnLabels = {
      quote_no: 'No.',
      customer_no: 'Sell-to Customer No.',
      sell_to_name: 'Sell-to Customer Name',
      external_document_no: 'External Document No.',
      sell_to_contact: 'Sell-to Contact',
      order_date: 'Posting Date',
      due_date: 'Due Date',
      requested_delivery_date: 'Requested Delivery Date',
      total_amount: 'Amount',
      location_code: 'Location Code',
      assigned_user_id: 'Assigned User ID',
      quote_valid_until_date: 'Quote Valid To Date'
    };
    const purchaseType = state.table === 'purchase_headers' && state.filterColumn === 'document_type'
      ? state.filterValue : null;
    if (state.table === 'sales_headers' && salesType === 'QUOTE') toolbar.append(makeOrder, selectionHint);
    if (salesLineTables.has(state.table)) toolbar.append(bulkDeleteLines);
    if (state.table === 'sales_headers' && ['RETURN_ORDER','INVOICE'].includes(salesType)) toolbar.append(erpButton(
      'Thêm ' + salesDocumentTitles[salesType],
      () => openErpForm(salesType === 'RETURN_ORDER' ? 'sales_returns' : 'sales_invoices'),
      'px-3 py-2 bg-indigo-600 text-white rounded-lg text-sm'));
    if (state.table === 'purchase_headers') toolbar.append(erpButton(
      'Thêm ' + ({ORDER: 'Purchase Order', QUOTE: 'Purchase Quote', INVOICE: 'Purchase Invoice'}[purchaseType] || 'Purchase Order'),
      () => openErpForm('purchase_headers', null, null, purchaseType || 'ORDER'),
      'px-3 py-2 bg-indigo-600 text-white rounded-lg text-sm'));
    if (state.table === 'sales_document_lines') toolbar.append(erpButton('Thêm dòng',()=>{
      const dialog=hrDialog('Thêm Sales Line'),content=erpElement('div',null,'hr-form');content.append(erpElement('h2','Chọn loại dòng chứng từ'));
      [['sales_quote_lines','Báo giá'],['sales_order_lines','Đơn bán'],['sales_invoice_lines','Hóa đơn'],['sales_return_lines','Trả hàng']].forEach(([table,label])=>content.append(erpButton(label,()=>{dialog.close();openErpForm(table);})));content.append(erpButton('Hủy',()=>dialog.close()));dialog.append(content);dialog.showModal();
    },'px-3 py-2 bg-indigo-600 text-white rounded-lg text-sm'));
    const canAddGeneric = state.table === 'sales_headers'
      ? !salesType || ['ORDER', 'QUOTE'].includes(salesType)
      : !erpReadOnlyTables.has(state.table);
    if (canAddGeneric) toolbar.append(erpButton(state.table === 'items'
      ? 'Tạo Item mới' : state.table === 'sales_headers'
        ? 'Thêm ' + salesDocumentTitles[salesType || 'QUOTE']
        : 'Thêm ' + tableLabels[state.table].toLowerCase(), () => openErpForm(state.table),
      'px-3 py-2 bg-indigo-600 text-white rounded-lg text-sm'));
    panel.append(toolbar);
    const wrapper = erpElement('div', null, 'overflow-auto max-h-[60vh] bg-white border rounded-xl');
    const table = erpElement('table', null, 'min-w-full text-sm text-left whitespace-nowrap');
    const head = erpElement('thead', null, 'bg-slate-100 sticky top-0');
    const heading = erpElement('tr', null);
    const leadingColumns = state.table==='sales_returns'
      ? ['return_no', 'sales_order_id', 'location_id', 'return_date', 'status']
      : state.table === 'purchase_headers'
      ? ['document_no', 'document_type', 'vendor_name', 'order_date', 'status', 'total_amount']
      : state.table === 'purchase_lines'
      ? ['document_no', 'line_no', 'item_no', 'description', 'quantity', 'unit_price', 'line_amount']
      : state.table==='inventory'
      ? ['item_code','item_name','location_name','quantity','reserved_quantity','available_quantity','unit','average_cost']
      : isSalesQuoteList
        ? ['quote_no', 'customer_no', 'sell_to_name', 'external_document_no', 'sell_to_contact', 'order_date',
          'due_date', 'requested_delivery_date', 'total_amount', 'location_code', 'assigned_user_id', 'quote_valid_until_date']
      : state.table === 'sales_headers'
        ? ['quote_no', 'customer_no', 'sell_to_name', 'external_document_no', 'location_code', 'assigned_user_id', 'document_date', 'status']
      : state.table==='inventory_ledger'
        ? ['item_name','location_name','document_type','quantity_in','quantity_out','balance_quantity','unit_cost']
        : data.columns.filter(column => !['id', 'created_at', 'updated_at'].includes(column));
    const visibleColumns = isSalesQuoteList
      ? leadingColumns.filter(column => data.columns.includes(column))
      : [...new Set([...leadingColumns, ...data.columns])].filter(column => data.columns.includes(column)
      && !hiddenSalesLineMetadata.has(column)
      && !(state.table === 'sales_document_lines' && column === 'line_amount')
      && !(state.table === 'purchase_headers' && column === 'vendor_id')
      && !(state.table === 'sales_headers' && ['customer_id', 'location_id'].includes(column))
      && !(state.table === 'inventory' && column === 'id')
      && !(state.table === 'inventory' && ['item_id', 'product_id', 'location_id'].includes(column))
      && !(state.table === 'locations' && ['id', 'location_id'].includes(column)));
    wrapper.tabIndex = 0;
    wrapper.setAttribute('role', 'region');
    wrapper.setAttribute('aria-label', 'Bảng ' + tableLabels[state.table] + ' — cuộn ngang để xem tất cả cột');
    heading.append(erpElement('th', 'Thao tác', 'px-4 py-3 sticky left-0 bg-slate-100 text-center'));
    if (state.table === 'purchase_headers') heading.firstElementChild.prepend(purchaseSelectAll);
    visibleColumns.forEach(column => {
      const label = isSalesQuoteList ? salesQuoteColumnLabels[column]
        : state.table === 'sales_document_lines' && column === 'item_no' ? 'No.'
          : state.table === 'sales_document_lines' && column === 'line_amount' ? 'Thành tiền (VAT)' : null;
      const th = erpElement('th', label || columnLabels[column] || column, 'px-4 py-3'); th.title = column; heading.append(th);
    });
    head.append(heading);
    const body = erpElement('tbody', null, 'divide-y');
    data.rows.forEach(row => {
      const tr = erpElement('tr', null, 'hover:bg-indigo-50 cursor-pointer');
      tr.tabIndex = 0;
      tr.setAttribute('aria-label', (state.table === 'sales_headers' ? 'Chọn chứng từ bán ' : 'Xem bản ghi ') + row.id);
      tr.onclick = () => {
        if (state.table === 'sales_document_lines') {
          openErpTable('sales_headers', 'id', row.document_id);
          return;
        }
        if (state.table !== 'sales_headers') return showErpDetail(state.table, row, data.columns);
        if (convertingQuote) return;
        selectedQuote = row;
        quoteRows.forEach(entry => {
          const selected = entry.row.id === row.id;
          entry.radio.checked = selected;
          entry.tr.classList.toggle('bg-indigo-50', selected);
        });
        makeOrder.disabled = row.document_type !== 'QUOTE' || !!row.sales_order_id || row.status !== 'DRAFT';
        selectionHint.textContent = row.document_type !== 'QUOTE'
          ? 'Make Order chỉ áp dụng cho Sales Quotes.'
          : row.sales_order_id ? 'Báo giá này đã liên kết với Sales Order.'
          : `Đã chọn báo giá ${row.quote_no || row.id}`;
      };
      tr.onkeydown = event => { if (event.target === tr && ['Enter', ' '].includes(event.key)) { event.preventDefault(); tr.click(); } };
      const actions = erpElement('td', null, 'px-3 py-2 sticky left-0 bg-white text-center align-middle');
      if (state.table === 'purchase_headers') {
        const choose = erpElement('input'); choose.type = 'checkbox';
        choose.disabled = row.status !== 'DRAFT' || !canDeleteRecords();
        choose.setAttribute('aria-label', 'Chọn chứng từ ' + row.document_no);
        choose.onclick = event => event.stopPropagation();
        choose.onchange = () => {
          if (choose.checked) purchaseSelected.set(String(row.id), row); else purchaseSelected.delete(String(row.id));
          syncPurchases();
        };
        purchaseChecks.push({input: choose, row});
        const edit = erpButton('Sửa', event => { event.stopPropagation(); openErpForm('purchase_headers', row); });
        edit.disabled = row.status !== 'DRAFT';
        const remove = erpButton('Xóa', event => { event.stopPropagation(); deleteErpRow('purchase_headers', row, event.currentTarget); });
        remove.disabled = row.status !== 'DRAFT' || !canDeleteRecords();
        actions.append(choose, edit, remove);
        syncPurchases();
      }
      if (salesLineTables.has(state.table)) {
        const sourceTable = state.table === 'sales_document_lines' ? row.source_table : state.table;
        const sourceId = state.table === 'sales_document_lines' ? row.source_line_id : row.id;
        const choose = erpElement('input');
        choose.type = 'checkbox';
        choose.disabled = !sourceTable || sourceId == null;
        if (choose.disabled) choose.title = 'Chứng từ chưa có dòng hàng để chọn; dùng nút Sửa/Xóa chứng từ bên cạnh.';
        choose.setAttribute('aria-label', `Chọn Sales Line ${sourceId ?? ''} để xóa`);
        if (!canDeleteRecords()) choose.title = 'Chỉ Administrator hoặc Director mới có quyền xóa Sales Lines.';
        choose.onclick = event => event.stopPropagation();
        choose.onchange = () => {
          const key = `${sourceTable}:${sourceId}`;
          if (choose.checked) selectedSalesLines.set(key, {table: sourceTable, id: sourceId});
          else selectedSalesLines.delete(key);
          bulkDeleteLines.textContent = `Xóa dòng đã chọn (${selectedSalesLines.size})`;
          bulkDeleteLines.disabled = selectedSalesLines.size === 0 || !canDeleteRecords();
        };
        actions.append(choose);
      }
      if (state.table === 'sales_document_lines' && !row.source_line_id) {
        const documentAction = async (event, deleting) => {
          event.stopPropagation();
          const button = event.currentTarget;
          button.disabled = true;
          try {
            const headers = await erpGet('/tables/sales_headers?' + new URLSearchParams({size: '1', filterColumn: 'id', filterValue: String(row.document_id)}));
            const header = headers.rows[0];
            if (!header) throw new Error('Chứng từ không còn tồn tại. Hãy làm mới danh sách.');
            const sourceTable = {QUOTE: 'sales_headers', ORDER: 'sales_orders', INVOICE: 'sales_invoices', RETURN_ORDER: 'sales_returns'}[header.document_type];
            const sourceId = header.document_type === 'QUOTE' ? header.id : header[{ORDER: 'sales_order_id', INVOICE: 'sales_invoice_id', RETURN_ORDER: 'sales_return_id'}[header.document_type]];
            if (!sourceTable || sourceId == null) throw new Error('Chứng từ chưa có liên kết nguồn hợp lệ.');
            const records = await erpGet('/tables/' + sourceTable + '?' + new URLSearchParams({size: '1', filterColumn: 'id', filterValue: String(sourceId)}));
            if (!records.rows.length) throw new Error('Chứng từ gốc không còn tồn tại.');
            if (deleting) {
              button.disabled = false;
              await deleteErpRow(sourceTable, records.rows[0], button, () => renderErpData());
            } else await openErpForm(sourceTable, records.rows[0], () => renderErpData());
          } catch (error) { notifyCustomer(error.message); }
          finally { button.disabled = false; }
        };
        const edit = erpButton('Sửa', event => documentAction(event, false));
        edit.title = 'Chưa có dòng hàng — sửa chứng từ';
        const remove = salesLineDeleteButton('Xóa', event => documentAction(event, true));
        remove.title = 'Chưa có dòng hàng — xóa chứng từ';
        actions.append(edit, remove);
      }
      if (state.table === 'sales_document_lines' && row.source_table && row.source_line_id != null) {
        actions.append(erpButton('Sửa', async event => {
          event.stopPropagation();
          try {
            const source = await erpGet(`/tables/${encodeURIComponent(row.source_table)}?size=1&filterColumn=id&filterValue=${encodeURIComponent(row.source_line_id)}`);
            if (!source.rows.length) throw new Error('Sales Line không còn tồn tại.');
            await openErpForm(row.source_table, source.rows[0], () => renderErpData());
          } catch (error) { notifyCustomer(error.message); }
        }));
        actions.append(salesLineDeleteButton('Xóa', event => {
          event.stopPropagation();
          deleteErpRow(row.source_table, {id: row.source_line_id}, event.currentTarget, () => renderErpData());
        }));
      }
      if (!erpReadOnlyTables.has(state.table)) actions.append(erpButton('Sửa', event => { event.stopPropagation(); openErpForm(state.table, row); }),
        erpButton('Xóa', event => { event.stopPropagation(); deleteErpRow(state.table, row, event.currentTarget); }));
      if (state.table === 'sales_headers') {
        const sourceTable={QUOTE:'sales_headers',ORDER:'sales_orders',RETURN_ORDER:'sales_returns',INVOICE:'sales_invoices'}[row.document_type];
        const sourceId=row.document_type==='QUOTE'?row.id:row[{ORDER:'sales_order_id',RETURN_ORDER:'sales_return_id',INVOICE:'sales_invoice_id'}[row.document_type]];
        const action=async(event,deleting)=>{
          event.stopPropagation();const button=event.currentTarget;button.disabled=true;
          try{const source=await erpGet(`/tables/${sourceTable}?size=1&filterColumn=id&filterValue=${encodeURIComponent(sourceId)}`);if(!source.rows.length)throw new Error('Chứng từ gốc không còn tồn tại.');
            if(deleting){button.disabled=false;await deleteErpRow(sourceTable,source.rows[0],button,()=>renderErpData());}
            else await openErpForm(sourceTable,source.rows[0],()=>renderErpData());
          }catch(error){notifyCustomer(error.message);}finally{button.disabled=false;}
        };
        if(sourceTable&&sourceId!=null){actions.append(erpButton('Sửa',event=>action(event,false)),erpButton('Xóa',event=>action(event,true)));}
        const radio = erpElement('input', null, 'mr-3');
        radio.type = 'radio'; radio.name = 'selected-sales-quote'; radio.value = row.id;
        radio.setAttribute('aria-label', 'Chọn chứng từ bán ' + (row.quote_no || row.id));
        radio.onchange = () => tr.click();
        quoteRows.push({row, tr, radio});
        actions.prepend(radio);
        actions.append(erpButton('View', event => { event.stopPropagation(); showErpDetail(state.table, row, data.columns); }));
      }
      tr.append(actions);
      visibleColumns.forEach(column => { const value = erpValue(row[column],column), cell = erpElement('td', value, 'px-4 py-3 max-w-xs truncate'); cell.title = value; tr.append(cell); });
      body.append(tr);
    });
    if (!data.rows.length) {
      const row = erpElement('tr', null), cell = erpElement('td', state.search || state.filterColumn ? 'Không có bản ghi phù hợp.' : 'Bảng này chưa có dữ liệu.', 'p-6 text-slate-500');
      cell.colSpan = visibleColumns.length + 1; row.append(cell); body.append(row);
    }
    table.append(head, body); wrapper.append(table); panel.append(wrapper);
    const pages = Math.max(1, Math.ceil(data.total / state.size));
    const pager = erpElement('div', null, 'flex gap-3 items-center');
    const previous = erpButton('Trước', () => { erpState.page--; renderErpData(); }); previous.disabled = state.page === 0;
    const next = erpButton('Sau', () => { erpState.page++; renderErpData(); }); next.disabled = state.page + 1 >= pages;
    const size = erpElement('select', null, 'border rounded-lg p-2'); size.setAttribute('aria-label', 'Số dòng mỗi trang');
    [25, 50, 100].forEach(value => { const option = erpElement('option', value + ' dòng'); option.value = value; option.selected = value === state.size; size.append(option); });
    size.onchange = () => { erpState.size = Number(size.value); erpState.page = 0; renderErpData(); };
    pager.append(previous, erpElement('span', `Trang ${state.page + 1}/${pages}`), next, size); panel.append(pager);
  } catch (error) {
    if (version !== erpRequestVersion) return;
    panel.replaceChildren(erpElement('p', 'Không tải được dữ liệu. Kiểm tra backend rồi thử lại.', 'text-rose-600'), erpButton('Thử lại', renderErpData));
  }
}
function erpDecimal(value,money=false) {
  const match=String(value).match(/^(-?)(\d+)(?:\.(\d+))?$/);
  if(!match)return String(value);
  let [,sign,whole,fraction='']=match;
  whole=whole.replace(/^0+(?=\d)/,'');
  if(money){whole=(BigInt(whole)+(fraction[0]>='5'?1n:0n)).toString();fraction='';}
  else fraction=fraction.replace(/0+$/,'');
  if(whole==='0'&&!fraction)sign='';
  return sign+whole.replace(/\B(?=(\d{3})+(?!\d))/g,'.')+(fraction?','+fraction:'')+(money?' ₫':'');
}
function erpPrice(value) {
  const match=String(value).match(/^(-?)(\d+)(?:\.(\d+))?$/);
  if(!match)return String(value);
  let [,sign,whole,fraction='']=match;
  whole=whole.replace(/^0+(?=\d)/,'');
  let cents=fraction.padEnd(2,'0').slice(0,2);
  if(fraction[2]>='5') {
    const rounded=(BigInt(whole)*100n+BigInt(cents)+1n).toString().padStart(3,'0');
    whole=rounded.slice(0,-2); cents=rounded.slice(-2);
  }
  if(whole==='0'&&cents==='00')sign='';
  const fractionText=cents==='00'?'':','+cents.replace(/0+$/,'');
  return sign+whole.replace(/\B(?=(\d{3})+(?!\d))/g,'.')+fractionText;
}
function erpIsPriceField(name) {
  return /^(unit_price|unit_cost|average_cost|purchase_price|sale_price)$/.test(name);
}
function erpConfigurePriceInput(input) {
  input.type = 'text';
  input.inputMode = 'decimal';
  input.pattern = '([0-9]{1,3}(\\.[0-9]{3})+(,[0-9]{1,2})?|[0-9]+([.,][0-9]{1,2})?)';
  input.placeholder = '';
  input.removeAttribute('title');
}
function erpNormalizePrice(value) {
  const raw = String(value).trim();
  if (/^\d{1,3}(?:\.\d{3})+(?:,\d{1,2})?$/.test(raw))
    return raw.replaceAll('.', '').replace(',', '.');
  if (/^\d+(?:[.,]\d{1,2})?$/.test(raw))
    return raw.replace(',', '.');
  throw new Error('Giá phải là số hợp lệ, ví dụ 1.500.000 hoặc 1.500.000,50.');
}
function erpValue(value,column='') {
  if (value === null || value === undefined) return '—';
  if (column === 'document_type') return ({
    ORDER: 'Order', QUOTE: 'Quote', INVOICE: 'Invoice',
    RETURN_ORDER: 'Return', RETURN: 'Return', CREDIT_MEMO: 'Credit Memo'
  })[value] || String(value);
  if (column === 'line_status') return value === 'MISSING' ? 'Chưa có dòng' : 'Có dòng';
  if (typeof value === 'boolean') return value ? 'Có' : 'Không';
  if(column==='item_category')return ({INVENTORY:'Inventory',SERVICE:'Service',NON_INVENTORY:'Non-Inventory'})[value]||String(value);
  if(column==='item_type')return ({PURCHASED:'Purchased',MANUFACTURED:'Manufactured'})[value]||String(value);
  if(/^(unit_price|unit_cost|average_cost|purchase_price|sale_price)$/.test(column))return erpPrice(value);
  if(/^(subtotal|balance|debit|credit|amount|.*_amount)$/.test(column))return erpDecimal(value,true);
  if(/quantity|^(consumed_quantity|quantity_per_unit|vat_rate|discount_percent|vat_percent)$/.test(column))return erpDecimal(value);
  return String(value);
}
function showPurchaseDocumentLines(detail, header) {
  const section = erpElement('section', null, 'profile-section');
  section.append(erpElement('h3', 'Purchase Lines'));
  const status = erpElement('p', 'Đang tải dòng hàng…', 'text-sm text-slate-500');
  section.append(status);
  section.append(erpButton('Mở danh sách Purchase Lines', () =>
    openErpTable('purchase_lines', 'purchase_header_id', header.id)));
  detail.append(section);
  erpGet('/tables/purchase_lines?' + new URLSearchParams({
    size: '100', filterColumn: 'purchase_header_id', filterValue: String(header.id)
  })).then(result => {
    status.textContent = result.total ? `${result.total} dòng hàng` : 'Chứng từ chưa có dòng hàng.';
    if (!result.rows.length) return;
    const wrapper = erpElement('div', null, 'overflow-auto mt-3');
    const table = erpElement('table', null, 'w-full text-sm text-left');
    const heading = erpElement('tr');
    ['Dòng', 'Mã Item', 'Mô tả', 'Location Code', 'Đơn vị', 'Số lượng', 'Đơn giá', 'Thành tiền'].forEach(caption =>
      heading.append(erpElement('th', caption, 'p-2')));
    const head = erpElement('thead');
    head.append(heading);
    const body = erpElement('tbody');
    result.rows.forEach(line => {
      const tr = erpElement('tr');
      [line.line_no, line.item_no, line.description, line.location_code, line.unit_of_measure, line.quantity, line.unit_price, line.line_amount]
        .forEach((value, index) => tr.append(erpElement('td', erpValue(value, [
          'line_no', 'item_no', 'description', 'location_code', 'unit_of_measure', 'quantity', 'unit_price', 'line_amount'
        ][index]), 'p-2')));
      body.append(tr);
    });
    table.append(head, body);
    wrapper.append(table);
    section.append(wrapper);
  }).catch(error => {
    status.textContent = `Không tải được Purchase Lines: ${error.message}`;
    status.classList.add('text-rose-600');
  });
}
function showErpDetail(table, row, columns) {
  const dialog = document.getElementById('erp-detail-dialog');
  const isSalesDocument = table === 'sales_headers' || table === 'sales_orders';
  const detail = isSalesDocument ? document.getElementById('tab-data') : dialog;
  detail.classList.add('profile-card');
  if (isSalesDocument) detail.replaceChildren();
  const closeBar = profileElement('div', null, 'profile-close');
  closeBar.append(profileAction(isSalesDocument ? '← Quay lại danh sách' : 'Đóng hồ sơ ×',
    () => isSalesDocument ? renderErpData() : dialog.close()));
  const name = row.item_name || row.vendor_name || row.name || row.account_name || row.description || tableLabels[table];
  const code = row.customer_no || row.quote_no || row.order_no || row.invoice_no || row.document_no || row.code || row.item_no || row.vendor_no || row.entry_no || ('ID ' + row.id);
  const status = row.status || (row.blocked != null ? (row.blocked ? 'Tạm khóa' : 'Đang hoạt động') : row.active != null ? (row.active ? 'Hoạt động' : 'Ngừng hoạt động') : null);
  const header = profileHeader(tableLabels[table] + ' · Hồ sơ chi tiết', name, code, status, row.blocked === true || row.active === false || (row.status && !['OPEN', 'POSTED', 'INVOICED'].includes(row.status)));
  header.querySelector('h2').id = 'erp-detail-title';
  detail.replaceChildren(closeBar, header);
  if (table === 'sales_headers') {
    if (row.document_type === 'QUOTE') {
      const quoteActions = erpElement('div', null, 'profile-actions');
      if (!row.sales_order_id) {
        quoteActions.append(profileAction('Make Order',
          event => makeSalesQuoteOrder(row, event.currentTarget), 'primary'));
        const quoteError = erpElement('p', '', 'text-rose-600 text-sm');
        quoteError.id = 'sales-quote-conversion-error'; quoteError.setAttribute('role', 'alert');
        quoteActions.append(quoteError);
      }
      if (quoteActions.childElementCount) detail.append(quoteActions);
    }
  }
  const groups = { 'Thông tin chung': [], 'Thông tin liên hệ': [], 'Thông tin tài chính & số lượng': [], 'Thời gian & theo dõi': [] };
  columns.forEach(column => {
    if (table === 'inventory' && column === 'id') return;
    if (table === 'sales_headers' && ['customer_id', 'location_id'].includes(column)) return;
    const group = /phone|email|address|city/.test(column) ? 'Thông tin liên hệ'
      : /amount|price|cost|quantity|balance|debit|credit|rate|percent|subtotal|currency|payment_terms|payment_method/.test(column) ? 'Thông tin tài chính & số lượng'
      : /_at$|_date$/.test(column) ? 'Thời gian & theo dõi' : 'Thông tin chung';
    groups[group].push([columnLabels[column] || column, row[column] == null ? null : erpValue(row[column],column)]);
  });
  Object.entries(groups).forEach(([title, fields]) => {
    if (fields.length) detail.append(profileSection(title, fields));
    if (title === 'Thông tin chung' && isSalesDocument) showSalesDocumentLines(detail, table, row);
    if (title === 'Thông tin chung' && table === 'purchase_headers') showPurchaseDocumentLines(detail, row);
  });
  const related = profileElement('section', null, 'profile-section');
  related.append(profileElement('h3', 'Dữ liệu liên quan'));
  const links = erpElement('div', null, 'flex flex-wrap gap-2');
  erpCatalog.relations.forEach(relation => {
    if (relation.source_table === table && row[relation.source_column] != null) links.append(erpButton(
      `${tableLabels[relation.target_table]} #${row[relation.source_column]} (${relation.source_column})`,
      () => openErpTable(relation.target_table, relation.target_column, row[relation.source_column])));
    if (relation.target_table === table && row[relation.target_column] != null) links.append(erpButton(
      `${tableLabels[relation.source_table]} (${relation.source_column})`,
      () => openErpTable(relation.source_table, relation.source_column, row[relation.target_column])));
  });
  if (!links.children.length) links.append(erpElement('p', 'Chưa có liên kết dữ liệu được khai báo.', 'text-sm text-slate-500'));
  related.append(links);
  const actions = profileElement('div', null, 'profile-actions');
  if (table === 'sales_returns' && row.status === 'DRAFT') {
    actions.append(profileAction('Thêm dòng trả hàng', async () => {
      await openErpTable('sales_return_lines', 'return_id', row.id); openErpForm('sales_return_lines');
    }, 'primary'));
    const returnError = profileElement('p', '', 'text-rose-600 text-sm'); returnError.setAttribute('role', 'alert');
    actions.append(returnError);
    actions.append(profileAction('Nhận hàng vào kho', async event => {
      const button = event.currentTarget;
      if (!await confirmAction('Xác nhận đã nhận hàng của phiếu ' + row.return_no + '? Tồn kho sẽ được cộng và phiếu được khóa chỉnh sửa.', {title: 'Nhận hàng trả', confirmText: 'Xác nhận nhận hàng', danger: false})) return;
      button.disabled = true; returnError.textContent = '';
      try { await erpWrite('sales_returns', row.id, 'PUT', {status: 'RECEIVED'}); dialog.close(); erpCatalog = null; await renderErpData(); notifyCustomer('Đã nhận hàng trả và cập nhật tồn kho.'); }
      catch (error) { returnError.textContent = error.message; }
      finally { button.disabled = false; }
    }));
  }
  if (!erpReadOnlyTables.has(table) && !(table === 'sales_returns' && row.status !== 'DRAFT')) actions.append(profileAction('Chỉnh sửa hồ sơ', () => { dialog.close(); openErpForm(table, row); }, 'primary'),
    profileAction('Xóa bản ghi', event => deleteErpRow(table, row, event.currentTarget), 'danger'));
  detail.append(related, actions);
  if (!isSalesDocument) dialog.showModal();
}

const customerInfo = document.querySelector('#tab-customers .flex.gap-2');
customerInfo.append(erpButton('Xem đầy đủ dữ liệu', () => openErpTable('customers')));
document.getElementById('erp-global-search').addEventListener('keydown', event => {
  if (event.key !== 'Enter') return;
  erpState.search = event.target.value.trim().slice(0, 200); erpState.page = 0;
  switchTab('data'); renderErpData();
});
authReady.then(()=>currentUser.role==='HR'?null:ensureErpCatalog()).catch(() => {
  document.getElementById('erp-order-count').textContent = '—';
  document.getElementById('erp-invoice-count').textContent = '—';
});
document.addEventListener('customers-changed', () => {
  erpCatalog = null;
  if (!document.getElementById('tab-data').classList.contains('hidden')) renderErpData();
  if (!document.getElementById('tab-dashboard').classList.contains('hidden')) renderErpDashboard(true);
});
