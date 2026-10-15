// Apply one explicit toggle behavior to every form section, including added Lines.
function configureDocumentSectionToggles(form) {
  const configure = () => form.querySelectorAll('details.document-section').forEach(section => {
    const summary = section.querySelector(':scope > summary');
    if (!summary || summary.dataset.explicitToggle === 'true') return;
    summary.dataset.explicitToggle = 'true';
    summary.tabIndex = -1;
    summary.addEventListener('click', event => event.preventDefault());
    summary.addEventListener('keydown', event => {
      if (event.target === summary && ['Enter', ' '].includes(event.key)) event.preventDefault();
    });
    section.classList.add('document-section-expandable');
    // Existing buttons may expand optional fields instead of closing the section.
    if (summary.querySelector('.document-fields-toggle')) return;
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'document-fields-toggle';
    const sync = () => {
      button.textContent = section.open ? 'Show less' : 'Show more';
      button.setAttribute('aria-expanded', String(section.open));
    };
    button.addEventListener('click', event => {
      event.preventDefault(); event.stopPropagation();
      section.open = !section.open;
      sync();
    });
    section.addEventListener('toggle', sync);
    summary.append(button);
    sync();
  });
  configure();
  const observer = new MutationObserver(configure);
  observer.observe(form, {childList: true, subtree: true});
}
// Keep the original controls and handlers: presentation must not change a form's payload.
function enhanceDocumentForm(dialog) {
  const form = dialog.querySelector('form');
  if (!form) return;
  unifyFormLookups(form);
  if (form.documentRefresh) { form.documentRefresh(); return; }
  const title = form.querySelector('h2');
  const submit = form.querySelector('button[type="submit"]');
  if (!title || !submit) return;
  const titleWrapper = title.id === 'customer-form-title' ? title.parentElement : null;
  const node = (tag, className, text) => {
    const element = document.createElement(tag); element.className = className;
    if (text != null) element.textContent = text;
    return element;
  };
  dialog.classList.add('document-dialog'); form.classList.add('document-form');
  const toolbar = node('div', 'document-toolbar');
  const breadcrumb = node('span', 'document-breadcrumb', 'Không gian làm việc / Biểu mẫu');
  const actions = submit.parentElement; actions.classList.add('document-actions');
  toolbar.append(breadcrumb, actions);
  const heading = node('div', 'document-heading'); heading.append(title);
  heading.append(node('p', 'document-description', 'Nhập thông tin bên dưới. Kiểm tra các trường bắt buộc trước khi lưu.'));
  const layout = node('div', 'document-layout');
  const content = node('div', 'document-content');
  const sidebar = node('aside', 'document-sidebar');
  sidebar.setAttribute('aria-label', 'Tóm tắt biểu mẫu');
  const summary = node('div', 'document-summary');
  summary.append(node('h3', '', 'Trong biểu mẫu này'));
  const sectionLinks = node('nav', 'document-section-links'); sectionLinks.setAttribute('aria-label', 'Các phần của biểu mẫu');
  summary.append(sectionLinks);
  const progress = node('p', 'document-progress');
  const values = node('dl', 'document-summary-values');
  summary.append(progress, values, node('p', 'document-hint', 'Kiểm tra thông tin rồi nhấn Lưu. Các trường có dấu * là bắt buộc.'));
  sidebar.append(summary);
  // Customer's title lives inside a wrapper with a close button.
  if (titleWrapper && titleWrapper !== toolbar) {
    const close = titleWrapper.querySelector('button');
    if (close) { close.setAttribute('aria-label', 'Đóng biểu mẫu'); actions.prepend(close); }
    titleWrapper.remove();
  }
  const grids = [...form.querySelectorAll('.hr-fields, #customer-fields, :scope > .grid')];
  const sectionName = input => {
    const name = input.name.replaceAll('_', '').toLowerCase();
    if (form.dataset.itemCard === 'true') {
      if (/postinggroup|unitcost/.test(name)) return 'Costs & Posting';
      if (name === 'unitprice') return 'Prices & Sales';
      if (name === 'quantity') return 'Inventory';
      return 'Item';
    }
    if (form.dataset.purchaseDocument === 'true') {
      if (/^(id|createdat|updatedat)$/.test(name)) return 'System Information';
      if (/currency|payment|shipmentmethod|requestedreceipt|promisedreceipt|discount|vatamount|totalamount/.test(name)) return 'Invoice Details';
      return 'General';
    }
    if(form.dataset.salesDocument==='true') {
      const explicit=input.closest('label')?.dataset.salesGroup;if(explicit)return explicit;
      if(/^(id|createdat|updatedat|receivedat|salesorderid|salesreturnid|salesinvoiceid|relatedsalesorderid)$/.test(name))return 'System Information';
      if(/subtotal|discountamount|vatamount|totalamount/.test(name))return 'Lines & Totals';
      if(/currency|payment|postinggroup/.test(name))return 'Invoice Details';
      return 'General';
    }
    if (form.dataset.salesDocument === 'true' && /currency|payment|postinggroup|vatregistration|vatcountry|pricesincludingvat|bankaccount|dimension|directdebit/.test(name)) return 'Invoice Details';
    if (['paymentno', 'paymenttype'].includes(name)) return 'Thông tin chung';
    if (/address|city|country|postal|postcode|phone|email|contact|shipto|billto/.test(name)) return 'Liên hệ & Địa chỉ';
    if (/date|deadline|month|status|active|blocked/.test(name)) return 'Thời gian & Trạng thái';
    if (/price|amount|discount|tax|vat|rate|salary|allowance|bonus|deduction|cost|payment/.test(name)) return 'Giá trị & Thanh toán';
    if (input.tagName === 'TEXTAREA') return 'Nội dung & Ghi chú';
    return 'Thông tin chung';
  };
  grids.forEach(grid => {
    const groups = new Map();
    [...grid.children].forEach(label => {
      const input = label.querySelector('[name]');
      if (!input) return;
      const name = sectionName(input);
      if (!label.querySelector('span')) {
        const text = [...label.childNodes].filter(child => child.nodeType === Node.TEXT_NODE);
        const caption = node('span', '', text.map(child => child.textContent).join('').trim());
        text.forEach(child => child.remove()); label.prepend(caption);
      }
      if (!groups.has(name)) groups.set(name, []);
      label.classList.add('document-field');
      // Help text always follows controls, including asynchronously populated lookups.
      [...label.children].filter(child => child.tagName === 'SMALL').forEach(help => label.append(help));
      if (input.tagName === 'TEXTAREA') label.classList.add('document-field-wide');
      groups.get(name).push(label);
    });
    const sections = node('div', 'document-sections');
    ['Item','Inventory','Costs & Posting','Prices & Sales','General','Lines & Totals','Thông tin chung', 'Liên hệ & Địa chỉ', 'Thời gian & Trạng thái', 'Giá trị & Thanh toán', 'Nội dung & Ghi chú', 'Invoice Details','Shipping and Billing','System Information'].forEach(name => {
      if (!groups.has(name)) return;
      const section = node('details', 'document-section'); section.open = true;
      if (name === 'Invoice Details') section.dataset.invoiceDetails = 'true';
      const sectionHeading = node('summary', '', name);
      section.append(sectionHeading);
      const jump = node('button', '', name); jump.type = 'button';
      jump.onclick = () => { section.open = true; section.scrollIntoView({behavior: 'smooth', block: 'start'}); };
      sectionLinks.append(jump);
      const fields = node('div', 'document-fields'); fields.append(...groups.get(name));
      const extraFields = groups.get(name).filter((field, index) => index >= 4 && !field.querySelector('[required]'));
      if (extraFields.length) {
        section.classList.add('document-section-expandable');
        // Only the explicit Show more/less button changes field visibility.
        sectionHeading.tabIndex = -1;
        sectionHeading.addEventListener('click', event => event.preventDefault());
        sectionHeading.addEventListener('keydown', event => {
          if (event.target === sectionHeading && ['Enter', ' '].includes(event.key)) event.preventDefault();
        });
        const toggle = node('button', 'document-fields-toggle'); toggle.type = 'button';
        const setExpanded = expanded => {
          extraFields.forEach(field => field.classList.toggle('document-field-collapsed', !expanded));
          toggle.textContent = expanded ? 'Show less' : 'Show more';
          toggle.setAttribute('aria-expanded', String(expanded));
          section.open = true;
        };
        section.documentExpandFields = () => setExpanded(true);
        toggle.addEventListener('click', event => {
          event.preventDefault(); event.stopPropagation();
          setExpanded(toggle.getAttribute('aria-expanded') !== 'true');
        });
        sectionHeading.append(toggle);
        setExpanded(true);
      }
      section.append(fields); sections.append(section);
    });
    // Keep the original grid (and its ID) so existing code can still find it.
    grid.className = 'document-groups'; grid.append(sections);
  });
  content.append(...form.childNodes);
  layout.append(content, sidebar); form.append(toolbar, heading, layout);
  form.addEventListener('invalid', event => {
    let parent = event.target.parentElement;
    while (parent && parent !== form) {
      if (parent.tagName === 'DETAILS') { parent.open = true; parent.documentExpandFields?.(); }
      parent = parent.parentElement;
    }
  }, true);
  const refresh = () => {
    const controls = [...form.querySelectorAll('input[name], select[name], textarea[name]')].filter(input => !input.disabled && input.type !== 'hidden');
    const required = controls.filter(input => input.required);
    const filled = required.filter(input => input.type === 'checkbox' ? input.checked : input.value.trim() !== '');
    progress.textContent = required.length ? `${filled.length} / ${required.length} trường bắt buộc đã nhập` : 'Không có trường bắt buộc';
    values.replaceChildren();
    controls.filter(input => !['password', 'file', 'checkbox'].includes(input.type) && input.tagName !== 'TEXTAREA' && input.value.trim()).slice(0, 6).forEach(input => {
      const label = input.closest('label')?.querySelector('span')?.textContent?.replace(/\s*\*$/, '') || input.name;
      values.append(node('dt', '', label), node('dd', '', input.tagName === 'SELECT' ? input.selectedOptions[0]?.textContent : input.value));
    });
  };
  configureDocumentSectionToggles(form);
  form.documentRefresh = refresh;
  form.addEventListener('input', refresh); form.addEventListener('change', refresh); refresh();
}
