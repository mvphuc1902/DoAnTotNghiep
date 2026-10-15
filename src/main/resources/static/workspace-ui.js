function confirmAction(message, {title = 'Xác nhận xóa', confirmText = 'Xóa bản ghi', danger = true} = {}) {
  return new Promise(resolve => {
    const dialog = document.createElement('dialog'); dialog.className = 'action-dialog';
    const icon = document.createElement('div'); icon.className = 'action-symbol'; icon.textContent = danger ? '!' : '?';
    const heading = document.createElement('h2'); heading.textContent = title;
    heading.id = 'confirm-title-' + Date.now(); dialog.setAttribute('aria-labelledby', heading.id);
    const description = document.createElement('p'); description.textContent = message;
    description.id = heading.id + '-description'; dialog.setAttribute('aria-describedby', description.id);
    const actions = document.createElement('div'); actions.className = 'action-buttons';
    const cancel = document.createElement('button'); cancel.type = 'button'; cancel.textContent = 'Giữ lại';
    const accept = document.createElement('button'); accept.type = 'button'; accept.textContent = confirmText;
    accept.className = danger ? 'action-danger' : 'action-primary';
    let accepted = false;
    cancel.onclick = () => dialog.close(); accept.onclick = () => { accepted = true; dialog.close(); };
    dialog.addEventListener('close', () => { dialog.remove(); resolve(accepted); }, {once: true});
    actions.append(cancel, accept); dialog.append(icon, heading, description, actions); document.body.append(dialog);
    dialog.showModal(); cancel.focus();
  });
}

document.addEventListener('DOMContentLoaded', () => {
  const toggle = document.getElementById('navigation-toggle');
  const sidebar = document.getElementById('workspace-navigation');
  if (!toggle || !sidebar) return;
  const close = () => { document.body.classList.remove('navigation-open'); toggle.setAttribute('aria-expanded', 'false'); };
  toggle.addEventListener('click', () => {
    const open = document.body.classList.toggle('navigation-open'); toggle.setAttribute('aria-expanded', String(open));
  });
  sidebar.addEventListener('click', event => { if (event.target.closest('button')) close(); });
  document.addEventListener('keydown', event => { if (event.key === 'Escape') close(); });
  document.getElementById('navigation-scrim')?.addEventListener('click', close);
});
