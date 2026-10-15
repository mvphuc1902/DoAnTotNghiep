// Presentation only: keep the existing controls, validation and write payload.
function arrangeItemCard(form, row) {
  form.classList.add('item-card');
  form.querySelector('.document-breadcrumb').textContent = 'Items / Item Card';
  const title = form.querySelector('h2');
  title.textContent = row ? `${row.item_no || ''} · ${row.description || ''}` : 'Tạo Item mới';
  const groups = form.querySelector('.document-sections');
  const stock = form.querySelector('[data-item-inventory]');
  if (stock) {
    const inventory = erpElement('details', null, 'document-section');
    inventory.open = true;
    inventory.append(erpElement('summary', 'Inventory'));
    stock.querySelector('h3')?.remove();
    stock.className = 'item-stock-content';
    inventory.append(stock);
    const existing = [...groups.children].find(section => section.querySelector('summary')?.firstChild.textContent === 'Inventory');
    if (existing) {
      stock.prepend(existing.querySelector('.document-fields'));
      existing.replaceWith(inventory);
    } else groups.firstElementChild.after(inventory);
  }
  // Each FastTab has an explicit expand control, even if it has few fields.
  groups.querySelectorAll(':scope > .document-section').forEach(section => {
    if (section.querySelector('.document-fields-toggle')) return;
    const summary = section.querySelector('summary');
    const toggle = erpElement('button', 'Show less', 'document-fields-toggle');
    toggle.type = 'button'; toggle.setAttribute('aria-expanded', 'true');
    summary.append(toggle);
    section.classList.add('document-section-expandable');
    section.addEventListener('toggle', () => {
      toggle.textContent = section.open ? 'Show less' : 'Show more';
      toggle.setAttribute('aria-expanded', String(section.open));
    });
    toggle.addEventListener('click', event => {
      event.preventDefault(); event.stopPropagation(); section.open = !section.open;
    });
  });
}
