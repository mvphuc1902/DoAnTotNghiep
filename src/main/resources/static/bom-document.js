async function openBomDocument(row = null) {
  if (erpSaving) return;
  const version = ++erpFormVersion;
  const form = erpElement('form', null, 'space-y-4');
  const title = erpElement('h2', 'Định mức nguyên vật liệu (BOM)'); title.id = 'erp-edit-title';
  const grid = erpElement('div', null, 'grid');
  const error = erpElement('p', '', 'text-rose-600'); error.setAttribute('role', 'alert');
  const parentLabel = erpElement('label'), parent = erpElement('select'); parent.name = 'parent_item_id'; parent.required = true;
  const parentSearch = erpElement('input'); parentSearch.type = 'search'; parentSearch.placeholder = 'Tìm mã / tên thành phẩm…'; parentSearch.setAttribute('aria-label', 'Tìm thành phẩm');
  parentLabel.append(erpElement('span', 'Item thành phẩm *'), parentSearch, parent); grid.append(parentLabel);
  const save = erpButton('Lưu BOM'); save.type = 'submit'; save.disabled = true;
  const actions = erpElement('div'); actions.append(erpButton('Hủy', () => { if (!erpSaving) erpEditDialog.close(); }), save);
  form.append(title, grid, erpElement('p', 'Định mức bên dưới là lượng nguyên vật liệu cần để sản xuất 1 đơn vị thành phẩm. Lưu BOM chưa thay đổi tồn kho.'), error, actions);
  erpEditDialog.replaceChildren(form); if (!erpEditDialog.open) erpEditDialog.showModal(); enhanceDocumentForm(erpEditDialog);
  const section = erpElement('details', null, 'document-section sales-document'); section.open = true; section.append(erpElement('summary', 'Các dòng nguyên vật liệu'));
  const tools = erpElement('div', null, 'sales-line-tools');
  const search = erpElement('input'); search.type = 'search'; search.placeholder = 'Tìm mã / tên nguyên vật liệu…'; search.setAttribute('aria-label', 'Tìm nguyên vật liệu');
  const picker = erpElement('select'); picker.setAttribute('aria-label', 'Chọn Item nguyên vật liệu');
  const add = erpButton('+ Thêm dòng', () => {
    const item = materials.find(item => String(item.id) === picker.value);
    if (!item) return;
    if (lines.some(line => line.componentId === String(item.id))) { error.textContent = 'Vật liệu này đã có trong BOM. Hãy sửa số lượng trên dòng hiện có.'; return; }
    appendLine({...item, component_id: String(item.id), quantity: '1'}); dirty = true;
  }); add.disabled = true;
  tools.append(search, picker, add);
  const scroll = erpElement('div', null, 'sales-lines-scroll'), table = erpElement('table'), head = erpElement('thead'), tr = erpElement('tr');
  ['Mã vật liệu', 'Tên nguyên vật liệu', 'Đơn vị', 'Định mức / 1 thành phẩm', 'Thao tác'].forEach(label => tr.append(erpElement('th', label)));
  head.append(tr); const body = erpElement('tbody'); table.append(head, body); scroll.append(table);
  const status = erpElement('p', 'Chọn thành phẩm để xem hoặc tạo BOM.', 'document-hint'); status.setAttribute('role', 'status');
  section.append(tools, scroll, status); form.querySelector('.document-content').append(section); unifiedLookup(search, picker);
  let lines = [], expected = [], materials = [], selectedParent = '', dirty = false, loading = false, materialVersion = 0, parentVersion = 0;
  const alive = () => version === erpFormVersion && erpEditDialog.open;
  function appendLine(item) {
    const line = {componentId: String(item.component_id)}, tr = erpElement('tr');
    tr.append(erpElement('td', item.item_no || item.component_id), erpElement('td', item.description || '—'), erpElement('td', item.base_unit_of_measure || '—'));
    const td = erpElement('td'), quantity = erpElement('input'); quantity.type = 'number'; quantity.min = '0.001'; quantity.step = '0.001'; quantity.max = '999999999999999.999'; quantity.required = true; quantity.value = item.quantity;
    quantity.setAttribute('aria-label', 'Định mức ' + (item.description || item.item_no)); quantity.oninput = () => { dirty = true; }; line.quantity = quantity; td.append(quantity); tr.append(td);
    const action = erpElement('td'), remove = erpButton('Xóa dòng', () => { lines = lines.filter(value => value !== line); tr.remove(); dirty = true; status.textContent = `${lines.length} dòng nguyên vật liệu`; });
    remove.classList.remove('delete-only');
    remove.disabled = !canDeleteRecords() && expected.some(value => value.componentId === line.componentId); line.remove = remove;
    action.append(remove); tr.append(action); lines.push(line); body.append(tr); status.textContent = `${lines.length} dòng nguyên vật liệu`;
  }
  async function loadMaterials() {
    const request = ++materialVersion; add.disabled = true;
    try {
      const data = await erpGet('/tables/items?' + new URLSearchParams({size: 100, search: search.value}));
      if (!alive() || request !== materialVersion) return;
      materials = data.rows.filter(item => String(item.id) !== selectedParent && !item.blocked);
      picker.replaceChildren(); materials.forEach(item => { const option = erpElement('option', `${item.item_no} — ${item.description || ''}`); option.value = item.id; picker.append(option); });
      add.disabled = !selectedParent || loading || !materials.length;
      picker.title = data.total > 100 ? '100 kết quả đầu; nhập từ khóa để tìm thêm.' : '';
    } catch (failure) { if (alive()) error.textContent = failure.message; }
  }
  async function loadBom(id) {
    loading = true; save.disabled = true; add.disabled = true; error.textContent = ''; body.replaceChildren(); lines = []; expected = []; dirty = false;
    selectedParent = id;
    try {
      if (!id) { status.textContent = 'Chọn thành phẩm để xem hoặc tạo BOM.'; return; }
      const data = await erpGet('/bom/' + encodeURIComponent(id));
      if (!alive() || selectedParent !== id) return;
      expected = data.map(item => ({componentId: String(item.component_id), quantity: item.quantity}));
      data.forEach(appendLine); status.textContent = `${lines.length} dòng nguyên vật liệu`; save.disabled = false;
    } catch (failure) { error.textContent = failure.message; }
    finally { loading = false; loadMaterials(); }
  }
  async function loadParents() {
    const request = ++parentVersion;
    try {
      const data = await erpGet('/tables/items?' + new URLSearchParams({size: 100, search: parentSearch.value, filterColumn: 'item_type', filterValue: 'MANUFACTURED'}));
      if (!alive() || request !== parentVersion) return;
      const previous = parent.selectedOptions[0]?.textContent;
      parent.replaceChildren(erpElement('option', 'Chọn Item thành phẩm')); parent.options[0].value = '';
      data.rows.filter(item => !item.blocked).forEach(item => { const option = erpElement('option', `${item.item_no} — ${item.description || ''}`); option.value = item.id; parent.append(option); });
      if (selectedParent && ![...parent.options].some(option => option.value === selectedParent)) { const option = erpElement('option', previous || 'Item #' + selectedParent); option.value = selectedParent; parent.append(option); }
      parent.value = selectedParent; form.documentRefresh();
    } catch (failure) { error.textContent = failure.message; }
  }
  parent.onchange = async () => {
    const next = parent.value; parent.disabled = true;
    try {
      if (dirty && !await confirmAction('Đổi thành phẩm sẽ bỏ các thay đổi BOM chưa lưu.', {title: 'Đổi thành phẩm', confirmText: 'Đổi thành phẩm', danger: false})) { parent.value = selectedParent; return; }
      await loadBom(next);
    } finally { parent.disabled = false; form.documentRefresh(); }
  };
  let parentTimer, materialTimer;
  parentSearch.oninput = () => { clearTimeout(parentTimer); parentTimer = setTimeout(loadParents, 250); };
  search.oninput = () => { clearTimeout(materialTimer); materialTimer = setTimeout(loadMaterials, 250); };
  form.onsubmit = async event => {
    event.preventDefault(); if (erpSaving || loading || save.disabled) return;
    if (!lines.length) { error.textContent = 'Thêm ít nhất một dòng nguyên vật liệu.'; return; }
    if (!form.reportValidity()) return;
    const payload = {expected, lines: lines.map(line => ({componentId: line.componentId, quantity: line.quantity.value}))};
    erpSaving = true; form.querySelectorAll('button,input,select').forEach(input => input.disabled = true); error.textContent = '';
    try {
      const response = await fetch(erpApi + '/bom/' + selectedParent, {method: 'PUT', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(payload), signal: AbortSignal.timeout(20000)});
      if (!response.ok) { const data = await response.json().catch(() => ({})); throw new Error(data.message || 'Không lưu được BOM.'); }
      erpEditDialog.close(); erpCatalog = null; await renderErpData(); notifyCustomer('Đã lưu BOM gồm ' + lines.length + ' nguyên vật liệu.');
    } catch (failure) { error.textContent = failure.name === 'TimeoutError' ? 'Chưa nhận được kết quả. Mở lại BOM để kiểm tra trước khi lưu lại.' : failure.message; }
    finally { erpSaving = false; form.querySelectorAll('button,input,select').forEach(input => input.disabled = false); lines.forEach(line => line.remove.disabled = !canDeleteRecords() && expected.some(value => value.componentId === line.componentId)); }
  };
  selectedParent = String(row?.parent_item_id || (erpState.filterColumn === 'parent_item_id' ? erpState.filterValue : '') || '');
  await loadParents(); if (selectedParent) await loadBom(selectedParent); else await loadMaterials();
}
