function profileElement(tag, text, className = '') {
  const node = document.createElement(tag);
  node.className = className;
  if (text != null) node.textContent = text;
  return node;
}
function profileHeader(kind, name, code, status, muted = false) {
  const header = profileElement('header', null, 'profile-header');
  header.append(profileElement('p', kind, 'profile-eyebrow'));
  const identity = profileElement('div', null, 'profile-identity');
  const initials = String(name || kind).trim().split(/\s+/).slice(0, 2).map(word => word[0]).join('').toUpperCase();
  identity.append(profileElement('span', initials, 'profile-avatar'));
  const text = profileElement('div');
  text.append(profileElement('h2', name || 'Chưa có tên', 'profile-name'), profileElement('p', code, 'profile-code'));
  identity.append(text); header.append(identity);
  if (status) header.append(profileElement('span', status, 'profile-status' + (muted ? ' profile-status-muted' : '')));
  return header;
}
function profileSection(title, fields) {
  const section = profileElement('section', null, 'profile-section');
  section.append(profileElement('h3', title));
  const list = profileElement('dl', null, 'profile-fields');
  fields.forEach(([label, value]) => {
    const field = profileElement('div', null, 'profile-field');
    const missing = value === null || value === undefined || value === '';
    field.append(profileElement('dt', label), profileElement('dd', missing ? 'Chưa cập nhật' : String(value), missing ? 'profile-missing' : ''));
    list.append(field);
  });
  section.append(list); return section;
}
function profileAction(label, handler, variant = '') {
  const button = profileElement('button', label, 'profile-action ' + variant);
  if (variant === 'danger') button.classList.add('delete-only');
  button.type = 'button'; button.onclick = handler; return button;
}
