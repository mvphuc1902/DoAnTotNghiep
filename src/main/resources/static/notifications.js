(() => {
  const bell=document.getElementById('notification-bell'),badge=document.getElementById('notification-count');
  const dialog=hrNode('dialog',null,'hr-dialog notification-dialog');dialog.id='notification-dialog';dialog.setAttribute('aria-labelledby','notification-title');
  const heading=hrNode('div',null,'hr-heading'),title=hrNode('h2','Thông báo');title.id='notification-title';
  const close=profileAction('Đóng',()=>dialog.close());heading.append(title,close);
  const status=hrNode('p','','hr-subtitle');status.setAttribute('role','status');
  const list=hrNode('div'),markAll=profileAction('Đánh dấu tất cả đã đọc',()=>markEverything());
  dialog.append(heading,status,markAll,list);document.body.append(dialog);
  let data={notifications:[],unreadCount:0},pending=null,acting=false,timer=null,lastRender='',known=false;

  function render(force=false) {
    const count=Number(data.unreadCount)||0;
    badge.hidden=count===0;badge.textContent=count>99?'99+':String(count);
    bell.setAttribute('aria-label',count?`Thông báo: ${count} chưa đọc`:'Thông báo: không có thông báo chưa đọc');
    if(!dialog.open)return;
    const key=JSON.stringify(data);if(!force&&key===lastRender)return;lastRender=key;
    status.textContent=count?`${count} thông báo chưa đọc`:'Bạn đã đọc tất cả thông báo.';
    markAll.disabled=acting||count===0;list.replaceChildren();
    if(!data.notifications.length){list.append(hrNode('p','Chưa có thông báo mới.','hr-empty'));return;}
    data.notifications.forEach(row=>{
      const button=hrNode('button',null,'notification-entry'+(row.is_read?'':' unread'));button.type='button';button.disabled=acting;
      button.append(hrNode('strong',row.title),hrNode('span',row.kind==='RECRUITMENT'?'Bài tuyển dụng':'Tin / bài viết HR'),
        hrNode('small',(row.is_read?'Đã đọc':'Chưa đọc')+' · '+new Date(row.created_at).toLocaleString('vi-VN')));
      button.onclick=()=>openNotification(row);list.append(button);
    });
    if(data.notifications.length===50)list.append(hrNode('small','Hiển thị tối đa 50 thông báo, ưu tiên chưa đọc.','hr-subtitle'));
  }
  function refresh(force=false) {
    if(pending)return pending;
    if(acting&&!force)return Promise.resolve();
    pending=(async()=>{
      try{data=await hrApi('notifications');known=true;render(force);}
      catch(error){lastRender='';if(dialog.open)status.textContent='Không tải được thông báo. '+error.message;}
      finally{pending=null;}
    })();return pending;
  }
  async function openNotification(row) {
    if(acting)return;acting=true;if(pending)await pending;render(true);
    try{
      if(!await openHrFeed({kind:row.kind,id:row.source_id}))throw new Error('Bài viết không còn khả dụng.');
      await hrApi('notifications/'+encodeURIComponent(row.id)+'/read','POST');
      dialog.close();await refresh(true);
    }catch(error){status.textContent=error.message;}
    finally{acting=false;markAll.disabled=Number(data.unreadCount)===0;list.querySelectorAll('button').forEach(button=>button.disabled=false);}
  }
  async function markEverything() {
    if(acting||!data.notifications.length)return;
    const throughId=data.notifications.reduce((max,row)=>BigInt(row.id)>BigInt(max)?row.id:max,'0');
    acting=true;if(pending)await pending;render(true);
    try{await hrApi('notifications/read-all','POST',{throughId});await refresh(true);}
    catch(error){status.textContent=error.message;}
    finally{acting=false;markAll.disabled=Number(data.unreadCount)===0;list.querySelectorAll('button').forEach(button=>button.disabled=false);}
  }
  bell.onclick=()=>{if(dialog.open){dialog.close();return;}dialog.showModal();bell.setAttribute('aria-expanded','true');if(known)render(true);else status.textContent='Đang tải thông báo…';refresh(true);};
  dialog.addEventListener('close',()=>{bell.setAttribute('aria-expanded','false');});
  document.addEventListener('hr-content-changed',()=>refresh(true));
  document.addEventListener('visibilitychange',()=>{if(!document.hidden)refresh(true);});
  window.addEventListener('focus',()=>refresh(true));
  window.addEventListener('pagehide',()=>clearInterval(timer));
  authReady.then(()=>{refresh();timer=setInterval(()=>{if(!document.hidden)refresh();},2000);}).catch(()=>{});
})();
