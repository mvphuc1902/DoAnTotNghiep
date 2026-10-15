const hrFeedState={filter:'ALL',search:''};
async function openHrFeed(focus=null) {
  hrState.section='posts';const version=++hrState.version;switchTab('hr');
  document.getElementById('nav-hr-posts')?.classList.add('active');
  const root=document.getElementById('tab-hr');root.replaceChildren(hrNode('p','Đang tải bài viết…'));
  try{
    const [posts,jobs]=await Promise.all([hrApi('posts'),hrApi('recruitment')]);
    const entries=[...posts.map(row=>({kind:'POST',row})),...jobs.map(row=>({kind:'RECRUITMENT',row}))];
    if(focus){
      const row=await hrApi((focus.kind==='RECRUITMENT'?'recruitment/':'posts/')+encodeURIComponent(focus.id));
      if(!entries.some(entry=>entry.kind===focus.kind&&String(entry.row.id)===String(focus.id)))entries.push({kind:focus.kind,row});
      hrFeedState.filter='ALL';hrFeedState.search='';
    }
    if(version!==hrState.version)return false;
    entries.sort((a,b)=>new Date(b.row.updated_at||b.row.published_at||b.row.created_at||0)-new Date(a.row.updated_at||a.row.published_at||a.row.created_at||0));
    const heading=hrNode('div',null,'hr-heading');heading.append(hrNode('h2','Recruitment & News & Announcements'));
    const actions=hrNode('div',null,'hr-actions');
    if(hrPayrollManager())actions.append(profileAction('+ New post',()=>hrChoosePostType(),'primary'));
    heading.append(actions);
    const toolbar=hrNode('div',null,'hr-toolbar'),search=hrNode('input',null,'hr-input'),filter=hrNode('select',null,'hr-input');
    search.type='search';search.placeholder='Tìm bài tuyển dụng, tin tức, thông báo…';search.setAttribute('aria-label','Tìm bài viết');search.value=hrFeedState.search;
    filter.setAttribute('aria-label','Loại bài viết');
    [['ALL','Tất cả bài viết'],['RECRUITMENT','Tuyển dụng'],['NEWS','Tin tức'],['ANNOUNCEMENT','Thông báo']].forEach(([value,label])=>{const option=hrNode('option',label);option.value=value;filter.append(option);});filter.value=hrFeedState.filter;
    toolbar.append(search,filter,profileAction('Làm mới',()=>openHrFeed()));
    const list=hrNode('div',null,'hr-feed');
    root.replaceChildren(heading,hrNode('p','Tuyển dụng, tin tức và thông báo HR trên cùng một trang.','hr-subtitle'),toolbar,list);
    function render(){
      list.replaceChildren();
      const matches=entries.filter(({kind,row})=>{
        const type=kind==='RECRUITMENT'?kind:row.category;
        const text=[row.title,row.summary,row.content,row.description,row.requirements,row.department,row.location].filter(Boolean).join(' ').toLowerCase();
        return (hrFeedState.filter==='ALL'||hrFeedState.filter===type)&&text.includes(hrFeedState.search.toLowerCase());
      });
      matches.forEach(entry=>list.append(hrFeedCard(entry)));
      if(!matches.length)list.append(hrNode('p',entries.length?'Không có bài phù hợp.':'Chưa có bài viết.','hr-empty'));
    }
    search.oninput=()=>{hrFeedState.search=search.value;render();};filter.onchange=()=>{hrFeedState.filter=filter.value;render();};render();
    if(focus){const card=document.getElementById('hr-feed-'+focus.kind+'-'+focus.id);card?.scrollIntoView({block:'center'});card?.focus();}
    return true;
  }catch(error){if(version===hrState.version)root.replaceChildren(hrNode('p',error.message,'hr-error'),profileAction('Thử lại',()=>openHrFeed(focus)));return false;}
}
function hrChoosePostType() {
  const dialog=hrDialog('Chọn loại bài đăng'),body=hrNode('div',null,'hr-form');
  const title=hrNode('h2','Bạn muốn đăng bài gì?'),choices=hrNode('div',null,'hr-post-type-choices');
  const choose=(label,description,open)=>{
    const button=profileAction('',()=>{dialog.close();open();});
    button.append(hrNode('strong',label),hrNode('span',description));return button;
  };
  choices.append(
    choose('Tuyển dụng','Đăng vị trí, mô tả công việc, yêu cầu và hạn ứng tuyển.',()=>recruitmentForm()),
    choose('Tin tức / Thông báo','Đăng tin công ty hoặc thông báo nội bộ kèm hình ảnh.',()=>hrPostForm())
  );
  body.append(title,choices,profileAction('Hủy',()=>dialog.close()));dialog.append(body);dialog.showModal();
}
function hrFeedCard({kind,row}) {
  const recruitment=kind==='RECRUITMENT',path=recruitment?'recruitment':'posts';
  const card=hrNode('article',null,'profile-card');card.id='hr-feed-'+kind+'-'+row.id;card.tabIndex=-1;card.style.marginBottom='16px';
  card.append(profileHeader(recruitment?'RECRUITMENT':row.category==='NEWS'?'NEWS':'ANNOUNCEMENT',row.title,
    recruitment?row.department+' · '+row.location:(row.author_name||'HR')+' · '+hrDate(row.published_at||row.created_at),row.status));
  if(row.has_image){const image=hrNode('img',null,'recruitment-image');image.src='/api/hr/'+path+'/'+encodeURIComponent(row.id)+'/image';image.alt='Ảnh bài viết: '+row.title;image.loading='lazy';card.append(image);}
  if(recruitment)card.append(profileSection('Thông tin tuyển dụng',[
    ['Hình thức',row.employment_type.replaceAll('_',' ')],['Hạn ứng tuyển',hrDate(row.deadline)],['Mô tả',row.description],['Yêu cầu',row.requirements],['Email ứng tuyển',row.contact_email]
  ]));
  else{const summary=hrNode('div',row.summary||row.content.slice(0,200),'profile-section');summary.style.whiteSpace='pre-wrap';card.append(summary);}
  const actions=hrNode('div',null,'profile-actions');
  if(!recruitment)actions.append(profileAction('Đọc bài',()=>hrReadPost(row)));
  if(hrPayrollManager()){
    actions.append(profileAction('Sửa / Đăng bài',()=>recruitment?recruitmentForm(row):hrPostForm(row),'primary'));
    if(row.status!=='PUBLISHED')actions.append(profileAction('Xóa',async()=>{
      if(!await confirmAction('Xóa bài viết này?'))return;
      try{await hrApi(path+'/'+encodeURIComponent(row.id),'DELETE');await openHrFeed();}catch(error){notifyCustomer(error.message);}
    }));
  }
  card.append(actions);return card;
}
