const isLocalPreview = location.protocol === 'file:' ||
  (['localhost', '127.0.0.1', '[::1]'].includes(location.hostname) && location.port !== '8081');
const customerApiUrl = isLocalPreview ? 'http://localhost:8081/api/customers' : '/api/customers';
let customers = [];
let selectedCustomerId = null;
let editingCustomerId = null;
let savingCustomer = false;
let loadVersion = 0;
const customerFields = [
  ['customerNo', 'Mã khách hàng', 'text', true], ['name', 'Tên khách hàng', 'text', true],
  ['phone', 'Số điện thoại', 'tel'], ['email', 'Email', 'email'],
  ['address', 'Địa chỉ', 'text'], ['currencyCode', 'Mã tiền tệ', 'text'],
  ['paymentTermsCode', 'Điều khoản thanh toán', 'text'],
  ['paymentMethodCode', 'Phương thức thanh toán', 'text']
];
const customerForm = document.getElementById('customer-form');
const customerDialog = document.getElementById('customer-dialog');
customerFields.forEach(([name, title, type, required]) => {
  const label = document.createElement('label');
  label.className = 'block text-sm font-medium space-y-1';
  label.textContent = title + (required ? ' *' : '');
  const input = document.createElement('input');
  Object.assign(input, {name, type, required: !!required, maxLength: 255});
  input.className = 'block w-full border border-slate-300 rounded-lg px-3 py-2 focus:outline-indigo-500';
  label.append(input);
  document.getElementById('customer-fields').append(label);
});

async function customerRequest(path = '', options = {}) {
  let response;
  try {
    response = await fetch(customerApiUrl + path, {
      ...options, headers: {Accept: 'application/json', ...(options.body ? {'Content-Type': 'application/json'} : {})},
      signal: AbortSignal.timeout(15000)
    });
  } catch (error) {
    throw new Error('Không nhận được phản hồi từ máy chủ. Kiểm tra kết nối và tải lại danh sách trước khi thử lại.');
  }
  if (!response.ok) {
    const data = await response.json().catch(() => ({}));
    throw new Error(data.message || (response.status === 400
      ? 'Thông tin chưa hợp lệ. Kiểm tra các trường bắt buộc và email.' : `Không thực hiện được thao tác (HTTP ${response.status}).`));
  }
  return response.status === 204 ? null : response.json();
}

function notifyCustomer(message) {
  const notice = document.getElementById('customer-notice');
  notice.textContent = message;
  notice.classList.remove('hidden');
}

function customerTableMessage(message) {
  const cell = document.createElement('td');
  cell.colSpan = 6;
  cell.className = 'p-5 text-center text-slate-500';
  cell.textContent = message;
  const row = document.createElement('tr');
  row.append(cell);
  document.querySelector('#tab-customers tbody').replaceChildren(row);
}

async function loadCustomersFromDatabase() {
  const version = ++loadVersion;
  customerTableMessage('Đang tải khách hàng...');
  try {
    const data = await customerRequest();
    if (version !== loadVersion) return;
    if (!Array.isArray(data)) throw new Error('Danh sách khách hàng không đúng định dạng.');
    customers = data;
    if (!customers.some(c => c.id === selectedCustomerId)) selectedCustomerId = null;
    renderCustomers();
    showCustomerDetail();
  } catch (error) {
    if (version !== loadVersion) return;
    customers = [];
    selectedCustomerId = null;
    document.getElementById('customer-count').textContent = '—';
    showCustomerDetail();
    customerTableMessage(error.message + ' Bấm Customers để tải lại.');
  }
}

function customerButton(label, icon, action) {
  const button = document.createElement('button');
  button.type = 'button';
  button.title = label;
  button.setAttribute('aria-label', label);
  button.className = 'p-2 text-indigo-600 hover:bg-indigo-100 rounded';
  if (icon === 'fa-trash') button.classList.add('delete-only');
  const glyph = document.createElement('i');
  glyph.className = 'fa-solid ' + icon;
  button.append(glyph);
  button.addEventListener('click', event => { event.stopPropagation(); action(button); });
  return button;
}

function renderCustomers() {
  document.getElementById('customer-count').textContent = customers.length.toLocaleString('vi-VN');
  if (!customers.length) { customerTableMessage('Chưa có khách hàng. Bấm Thêm KH mới để đăng ký.'); return; }
  const rows = customers.map(customer => {
    const row = document.createElement('tr');
    row.className = 'cursor-pointer hover:bg-indigo-50 ' + (customer.id === selectedCustomerId ? 'bg-indigo-50' : '');
    row.tabIndex = 0;
    row.setAttribute('aria-label', 'Xem khách hàng ' + customer.name);
    const select = () => {
      selectedCustomerId = customer.id;
      document.querySelectorAll('#tab-customers tbody tr').forEach(r => r.classList.remove('bg-indigo-50'));
      row.classList.add('bg-indigo-50');
      showCustomerDetail();
    };
    row.addEventListener('click', select);
    row.addEventListener('keydown', event => {
      if (event.target === row && ['Enter', ' '].includes(event.key)) { event.preventDefault(); select(); }
    });
    [customer.customerNo, customer.name, customer.address, customer.phone,
      customer.blocked ? 'Tạm khóa' : 'Đang hoạt động'].forEach(value => {
      const cell = document.createElement('td');
      cell.className = 'py-3 px-4';
      cell.textContent = value || 'Chưa cập nhật';
      row.append(cell);
    });
    const statusCell = row.lastElementChild;
    const status = document.createElement('span');
    status.className = 'customer-status' + (customer.blocked ? ' muted' : '');
    status.textContent = statusCell.textContent; statusCell.replaceChildren(status);
    const actions = document.createElement('td');
    actions.className = 'px-4 text-right whitespace-nowrap';
    actions.append(customerButton('Sửa khách hàng', 'fa-pen-to-square', () => openCustomerForm(customer)),
      customerButton('Xóa khách hàng', 'fa-trash', button => deleteCustomer(customer, button)));
    row.append(actions);
    return row;
  });
  document.querySelector('#tab-customers tbody').replaceChildren(...rows);
}

function showCustomerDetail() {
  const panel = document.getElementById('customer-detail');
  panel.classList.add('profile-card');
  const customer = customers.find(c => c.id === selectedCustomerId);
  if (!customer) {
    panel.replaceChildren(profileHeader('Customer profile', 'Hồ sơ khách hàng', 'Chọn một khách hàng để xem hồ sơ'),
      profileSection('Thông tin hồ sơ', [['Hướng dẫn', 'Bấm vào một dòng trong danh sách khách hàng.']]));
    return;
  }
  panel.replaceChildren(
    profileHeader('Customer profile', customer.name, customer.customerNo || 'Chưa có mã', customer.blocked ? 'Tạm khóa' : 'Đang hoạt động', customer.blocked),
    profileSection('Thông tin liên hệ', [['Số điện thoại', customer.phone], ['Email', customer.email], ['Địa chỉ', customer.address]]),
    profileSection('Thông tin thanh toán', [['Mã tiền tệ', customer.currencyCode], ['Điều khoản thanh toán', customer.paymentTermsCode], ['Phương thức thanh toán', customer.paymentMethodCode]])
  );
  const related = profileElement('section', null, 'profile-section');
  related.append(profileElement('h3', 'Giao dịch liên quan'));
  const links = profileElement('div', null, 'flex flex-wrap gap-2');
  [['Đơn bán hàng', 'sales_headers'], ['Hóa đơn', 'sales_invoices'], ['Công nợ', 'customer_ledger']].forEach(([label, table]) => {
    links.append(profileAction(label, () => openErpTable(table, 'customer_id', customer.id)));
  });
  related.append(links);
  const actions = profileElement('div', null, 'profile-actions');
  actions.append(profileAction('Chỉnh sửa hồ sơ', () => openCustomerForm(customer), 'primary'),
    profileAction('Xóa khách hàng', event => deleteCustomer(customer, event.currentTarget), 'danger'));
  panel.append(related, actions);
}
function openCustomerForm(customer = null) {
  editingCustomerId = customer?.id ?? null;
  customerForm.reset();
  customerFields.forEach(([name]) => { customerForm.elements[name].value = customer?.[name] ?? ''; });
  customerForm.elements.blocked.checked = !!customer?.blocked;
  document.getElementById('customer-form-title').textContent = customer ? 'Chỉnh sửa khách hàng' : 'Đăng ký khách hàng';
  document.getElementById('customer-form-error').textContent = '';
  enhanceDocumentForm(customerDialog); customerDialog.showModal();
  customerForm.elements.customerNo.focus();
}

function closeCustomerForm() { if (!savingCustomer) customerDialog.close(); }
customerDialog.addEventListener('cancel', event => { if (savingCustomer) event.preventDefault(); });
customerForm.addEventListener('submit', async event => {
  event.preventDefault();
  if (savingCustomer) return;
  const data = Object.fromEntries(customerFields.map(([name]) => [name, customerForm.elements[name].value.trim()]));
  if (!data.customerNo || !data.name) {
    document.getElementById('customer-form-error').textContent = 'Vui lòng nhập mã và tên khách hàng.';
    return;
  }
  data.blocked = customerForm.elements.blocked.checked;
  savingCustomer = true;
  const submit = document.getElementById('customer-save');
  submit.disabled = true;
  submit.textContent = 'Đang lưu...';
  document.getElementById('customer-form-error').textContent = '';
  try {
    const saved = await customerRequest(editingCustomerId === null ? '' : '/' + editingCustomerId, {
      method: editingCustomerId === null ? 'POST' : 'PUT', body: JSON.stringify(data)
    });
    selectedCustomerId = saved.id;
    customerDialog.close();
    notifyCustomer('Đã lưu khách hàng thành công.');
    await loadCustomersFromDatabase();
    document.dispatchEvent(new Event('customers-changed'));
  } catch (error) {
    document.getElementById('customer-form-error').textContent = error.message;
  } finally {
    savingCustomer = false;
    submit.disabled = false;
    submit.textContent = 'Lưu khách hàng';
  }
});

async function deleteCustomer(customer, button) {
  if (!canDeleteRecords()) return;
  if (!await confirmAction(`Xóa khách hàng ${customer.customerNo} — ${customer.name}? Thao tác này không thể hoàn tác.`)) return;
  button.disabled = true;
  try {
    await customerRequest('/' + customer.id, {method: 'DELETE'});
    notifyCustomer('Đã xóa khách hàng.');
    await loadCustomersFromDatabase();
    document.dispatchEvent(new Event('customers-changed'));
  } catch (error) { notifyCustomer(error.message); }
  finally { button.disabled = false; }
}

authReady.then(()=>{if(currentUser.role!=='HR')loadCustomersFromDatabase();});
