async function openBomDocument(row = null) {
  if (erpSaving) return;
  const version = ++erpFormVersion;
  const form = erpElement('form', null, 'space-y-4');
  const title = erpElement('h2', 'Production BOM'); title.id = 'erp-edit-title';
  const grid = erpElement('div', null, 'grid');
  const error = erpElement('p', '', 'text-rose-600'); error.setAttribute('role', 'alert');
  const parentLabel = erpElement('label'), parent = erpElement('select'); parent.name = 'parent_item_id'; parent.required = true;
  const parentSearch = erpElement('input'); parentSearch.type = 'search'; parentSearch.placeholder = 'Search Items in Production Orders…'; parentSearch.setAttribute('aria-label', 'Search Items from Production Orders');
  parentLabel.append(erpElement('span', 'Item to manufacture *'), parentSearch, parent); grid.append(parentLabel);
  const save = erpButton('Save BOM'); save.type = 'submit'; save.disabled = true;
  const actions = erpElement('div'); actions.append(erpButton('Cancel', () => { if (!erpSaving) erpEditDialog.close(); }), save);
  form.append(title, grid, erpElement('p', 'BOM xác định các linh kiện sản xuất và số lượng cần để tạo ra một Item loại Sản xuất; tồn kho chỉ thay đổi khi hoàn tất Production Order.'), error, actions);
  erpEditDialog.replaceChildren(form); if (!erpEditDialog.open) erpEditDialog.showModal(); enhanceDocumentForm(erpEditDialog);
  const section = erpElement('details', null, 'document-section sales-document'); section.open = true; section.append(erpElement('summary', 'BOM Lines'));
  const tools = erpElement('div', null, 'sales-line-tools');
  const search = erpElement('input'); search.type = 'search'; search.placeholder = 'Tìm trong bảng Items…'; search.setAttribute('aria-label', 'Tìm Item thành phần trong Items');
  const picker = erpElement('select'); picker.setAttribute('aria-label', 'Chọn Item thành phần từ bảng Items');
  const emptyOption = erpElement('option', 'Tìm rồi chọn Item từ bảng Items'); emptyOption.value = ''; picker.append(emptyOption);
  const add = erpButton('+ Thêm dòng', () => {
    const item = materials.find(value => String(value.id) === picker.value);
    if (item) addComponentItem(item);
  });
  add.disabled = true;
  tools.append(search, picker, add);
  const scroll = erpElement('div', null, 'sales-lines-scroll'), table = erpElement('table'), head = erpElement('thead'), tr = erpElement('tr');
  ['Component No.', 'Component Name', 'Unit', 'Quantity per manufactured item', 'Action'].forEach(label => tr.append(erpElement('th', label)));
  head.append(tr); const body = erpElement('tbody'); table.append(head, body); scroll.append(table);
  const status = erpElement('p', 'Chọn Item sản xuất ở trên để thêm thành phần.', 'document-hint'); status.setAttribute('role', 'status');
  section.append(tools, scroll, status); form.querySelector('.document-content').append(section);
  unifiedLookup(search, picker);
  let lines = [], expected = [], materials = [], selectedParent = '', dirty = false, loading = false, materialsLoading = false, materialVersion = 0, parentVersion = 0;
  const alive = () => version === erpFormVersion && erpEditDialog.open;
  function updateComponentPicker() {
    picker.disabled = !selectedParent || loading || materialsLoading || materials.length === 0;
    add.disabled = picker.disabled || !picker.value
      || lines.some(line => line.componentId === picker.value);
  }
  picker.addEventListener('change', updateComponentPicker);
  function addComponentItem(item) {
    if (!selectedParent || loading || materialsLoading) return;
    if (lines.some(line => line.componentId === String(item.id))) {
      error.textContent = 'Item này đã có trong BOM. Hãy sửa số lượng tại dòng hiện có.';
      return;
    }
    error.textContent = '';
    appendLine({...item, component_id: String(item.id), quantity: '1'});
    dirty = true;
    picker.value = '';
    picker.dispatchEvent(new Event('change', {bubbles: true}));
    updateComponentPicker();
  }
  function renderComponentPicker() {
    const selected = picker.value;
    picker.replaceChildren();
    const prompt = erpElement('option', materialsLoading ? 'Đang tải Items…'
      : materials.length ? 'Chọn Item từ bảng Items' : 'Không có Item phù hợp');
    prompt.value = '';
    picker.append(prompt);
    materials.forEach(item => {
      const option = erpElement('option', `${item.item_no || ''} — ${item.description || ''}${item.base_unit_of_measure ? ` · ${item.base_unit_of_measure}` : ''}`);
      option.value = String(item.id);
      picker.append(option);
    });
    picker.value = materials.some(item => String(item.id) === selected) ? selected : '';
    updateComponentPicker();
  }
  function renderMaterialStatus() {
    if (materialsLoading) {
      status.textContent = 'Đang tra cứu Item từ bảng Items…';
    } else if (search.value.trim() && !materials.length) {
      status.textContent = 'Không tìm thấy Item loại Linh kiện sản xuất đang hoạt động. Kiểm tra Type, Inventory Category và trạng thái Tạm khóa trong Items.';
    } else if (!materials.length) {
      status.textContent = 'Chưa có Item loại Linh kiện sản xuất đang hoạt động. Hãy tạo Item với Type “Linh kiện sản xuất”, Inventory Category và Tạm khóa = Không.';
    } else if (!selectedParent) {
      status.textContent = 'Chọn Item sản xuất ở trên để thêm thành phần.';
    } else {
      status.textContent = `${lines.length} dòng BOM`;
    }
  }
  function appendLine(item) {
    const line = {componentId: String(item.component_id)}, tr = erpElement('tr');
    tr.append(erpElement('td', item.item_no || item.component_id), erpElement('td', item.description || '—'), erpElement('td', item.base_unit_of_measure || '—'));
    const td = erpElement('td'), quantity = erpElement('input'); quantity.type = 'number'; quantity.min = '0.001'; quantity.step = '0.001'; quantity.max = '999999999999999.999'; quantity.required = true; quantity.value = item.quantity;
    quantity.setAttribute('aria-label', 'Định mức ' + (item.description || item.item_no)); quantity.oninput = () => { dirty = true; }; line.quantity = quantity; td.append(quantity); tr.append(td);
    const action = erpElement('td'), remove = erpButton('Remove', () => {
      lines = lines.filter(value => value !== line);
      tr.remove();
      dirty = true;
      status.textContent = `${lines.length} dòng BOM`;
      renderComponentPicker();
    });
    remove.classList.remove('delete-only');
    remove.disabled = !canDeleteRecords() && expected.some(value => value.componentId === line.componentId); line.remove = remove;
    action.append(remove); tr.append(action); lines.push(line); body.append(tr); status.textContent = `${lines.length} dòng BOM`;
  }
  async function loadMaterials() {
    const request = ++materialVersion;
    materialsLoading = true;
    updateComponentPicker();
    renderMaterialStatus();
    try {
      const data = await erpGet('/tables/items?' + new URLSearchParams({
        size: 100, search: search.value, filterColumn: 'item_type', filterValue: 'PRODUCTION_COMPONENT'
      }));
      if (!alive() || request !== materialVersion) return;
      materials = data.rows.filter(item => String(item.id) !== selectedParent
        && item.item_category === 'INVENTORY' && !item.blocked);
      materialsLoading = false;
      renderComponentPicker();
      renderMaterialStatus();
      if (data.total > 100) status.textContent = 'Đang hiển thị 100 kết quả đầu; hãy nhập từ khóa cụ thể hơn.';
    } catch (failure) {
      if (!alive() || request !== materialVersion) return;
      error.textContent = failure.message;
      status.textContent = 'Không tải được Items. Hãy nhập lại từ khóa để thử lại.';
      materials = [];
      materialsLoading = false;
      renderComponentPicker();
    }
  }
  async function loadBom(id) {
    loading = true; save.disabled = true; error.textContent = ''; body.replaceChildren(); lines = []; expected = []; dirty = false;
    updateComponentPicker();
    selectedParent = id;
    try {
      if (!id) { status.textContent = 'Chọn Item sản xuất để xem hoặc tạo BOM.'; return; }
      const data = await erpGet('/bom/' + encodeURIComponent(id));
      if (!alive() || selectedParent !== id) return;
      expected = data.map(item => ({componentId: String(item.component_id), quantity: item.quantity}));
      data.forEach(appendLine); status.textContent = `${lines.length} dòng BOM`; save.disabled = false;
    } catch (failure) { error.textContent = failure.message; }
    finally { loading = false; loadMaterials(); }
  }
  async function loadParents() {
    const request = ++parentVersion;
    try {
      const data = await erpGet('/production-order-items?' + new URLSearchParams({search: parentSearch.value}));
      if (!alive() || request !== parentVersion) return;
      const previous = parent.selectedOptions[0]?.textContent;
      parent.replaceChildren(erpElement('option', 'Select manufactured Item from Production Orders')); parent.options[0].value = '';
      data.forEach(item => {
        const option = erpElement('option', `${item.item_no} — ${item.description || ''} · ${item.production_no}`);
        option.value = item.id;
        parent.append(option);
      });
      if (selectedParent && ![...parent.options].some(option => option.value === selectedParent)) {
        let label = previous;
        if (!label || label === 'Select manufactured Item from Production Orders') {
          const itemData = await erpGet('/tables/items?' + new URLSearchParams({
            size: 1, filterColumn: 'id', filterValue: selectedParent
          }));
          if (!alive() || request !== parentVersion) return;
          const item = itemData.rows[0];
          label = item ? `${item.item_no} — ${item.description || ''}` : 'Item #' + selectedParent;
        }
        const option = erpElement('option', label); option.value = selectedParent; parent.append(option);
      }
      parent.value = selectedParent; form.documentRefresh();
    } catch (failure) { error.textContent = failure.message; }
  }
  parent.onchange = async () => {
    const next = parent.value; parent.disabled = true;
    try {
      if (dirty && !await confirmAction('Switching the manufactured Item will discard unsaved BOM changes.', {title: 'Change manufactured Item', confirmText: 'Change Item', danger: false})) { parent.value = selectedParent; return; }
      await loadBom(next);
    } finally { parent.disabled = false; form.documentRefresh(); }
  };
  let parentTimer, materialTimer;
  parentSearch.oninput = () => { clearTimeout(parentTimer); parentTimer = setTimeout(loadParents, 250); };
  search.oninput = () => {
    clearTimeout(materialTimer);
    materialVersion++;
    materials = [];
    materialsLoading = true;
    renderComponentPicker();
    renderMaterialStatus();
    materialTimer = setTimeout(loadMaterials, 250);
  };
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
