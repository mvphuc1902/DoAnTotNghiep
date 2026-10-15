// Common Sales Header fields; source adapters persist them in sales_headers atomically.
async function appendSalesHeaderFields(form, grid, controls, table, row) {
  if (!['sales_headers','sales_orders','sales_returns','sales_invoices'].includes(table)) return () => ({});
  const [definitions, existing] = await Promise.all([
    erpGet('/sales-form/fields'), row ? erpGet('/sales-form/values?' + new URLSearchParams({table,id:row.id})) : Promise.resolve({})
  ]);
  const extras = new Map(), initial = new Map();
  const captions={order_no:'No.',quote_no:'No.',customer_id:'Customer No.',location_id:'Location Code',order_date:'Order Date',posting_date:'Posting Date',requested_delivery_date:'Requested Delivery Date',currency_code:'Currency Code',payment_terms_code:'Payment Terms Code',payment_method_code:'Payment Method Code',salesperson_code:'Salesperson Code',status:'Status',notes:'Notes'};
  for(const [name,input] of controls){const span=input.closest('label')?.querySelector('span');if(span&&captions[name])span.textContent=captions[name]+(input.required?' *':'')}
  for(const [name,category] of [['salesperson_code','SALESPERSON'],['payment_terms_code','PAYMENT_TERMS']]) {
    const original=controls.get(name);if(!original||original.disabled)continue;
    const select=erpElement('select');select.name=name;select.required=original.required;
    const empty=erpElement('option','Chưa chọn');empty.value='';select.append(empty);
    const options=await erpGet('/sales-form/options/'+category);
    options.forEach(choice=>{const option=erpElement('option',`${choice.code} · ${choice.name}`);option.value=choice.code;select.append(option)});
    if(original.value&&!options.some(c=>c.code===original.value)){const current=erpElement('option',original.value);current.value=original.value;select.append(current)}
    select.value=original.value;original.replaceWith(select);controls.set(name,select);
  }
  const optionsCache = new Map();
  await Promise.all(definitions.map(async field => {
    if (controls.has(field.name) || field.name === 'work_description') return;
    const label = erpElement('label',null,'block text-sm'); label.dataset.salesGroup=field.group;
    label.append(erpElement('span',field.label));
    const input = erpElement(field.lookup ? 'select' : field.type==='textarea' ? 'textarea' : 'input');
    input.name=field.name; input.dataset.salesHeaderField='true';
    if(input.tagName==='INPUT') input.type=field.type;
    if(field.lookup) {
      const search=erpElement('input');search.type='search';search.placeholder='Tìm '+field.label+'…';search.setAttribute('aria-label',field.label);label.append(search);
      const empty=erpElement('option','Chưa chọn');empty.value='';input.append(empty);
      if(!optionsCache.has(field.lookup)) optionsCache.set(field.lookup,erpGet('/sales-form/options/'+field.lookup));
      const choices=await optionsCache.get(field.lookup);
      choices.forEach(choice=>{const option=erpElement('option',`${choice.code} · ${choice.name}`);option.value=choice.code;input.append(option)});
      if(existing[field.name]&&!choices.some(c=>c.code===existing[field.name])) {const old=erpElement('option',existing[field.name]+' (đang lưu)');old.value=existing[field.name];input.append(old)}
      const help=erpElement('small',choices.length?'':'Chưa có dữ liệu trong danh mục Sales Setup.');label.append(help);
    }
    input.value=existing[field.name]??(field.name==='currency_code'?'VND':'');
    input.required=field.name==='currency_code'; initial.set(field.name,input.value);
    label.append(input); extras.set(field.name,input);
    // Restore definition order after concurrent lookup requests finish.
    field.element=label;
  }));
  definitions.forEach(field=>{if(field.element)grid.append(field.element)});
  let customerRequest=0;
  controls.get('sales_order_id')?.addEventListener('change',async()=>{
    if(table!=='sales_returns'&&table!=='sales_invoices')return;
    const request=++customerRequest,orderId=controls.get('sales_order_id').value;if(!orderId)return;
    try{
      const data=await erpGet('/sales-form/values?'+new URLSearchParams({table:'sales_orders',id:orderId}));
      if(request!==customerRequest)return;
      for(const [name,input] of extras)if(data[name]!=null)input.value=data[name];
      form.documentRefresh?.();
    }catch(error){notifyCustomer('Không tải được thông tin đơn gốc: '+error.message)}
  });
  controls.get('customer_id')?.addEventListener('change',async()=>{
    const request=++customerRequest,customerId=controls.get('customer_id').value;if(!customerId)return;
    try {
      const result=await erpGet('/tables/customers?'+new URLSearchParams({size:1,filterColumn:'id',filterValue:customerId}));
      if(request!==customerRequest||!result.rows.length)return;
      const customer=result.rows[0];
      for(const [target,source] of Object.entries({sell_to_name:'name',sell_to_address:'address',sell_to_phone:'phone',sell_to_email:'email'})) {
        const input=extras.get(target);if(input)input.value=customer[source]??'';
      }
      form.documentRefresh?.();
    }catch(error){notifyCustomer('Không tải được thông tin khách hàng: '+error.message)}
  });
  const read=()=>Object.fromEntries([...extras].filter(([name,input])=>!row||input.value!==initial.get(name)).map(([name,input])=>[name,input.value.trim()||null]));
  read.headerId=existing._header_id;
  return read;
}

function appendSalesDocumentActions(form,table,row,headerId) {
  if(!headerId)return;
  let dirty=false;form.addEventListener('input',()=>dirty=true);form.addEventListener('change',()=>dirty=true);
  const actions=form.querySelector('.document-actions');
  const action=(label,url,onSuccess)=>{
    const button=erpButton(label,async()=>{
      if(dirty){notifyCustomer('Lưu thay đổi trước khi thực hiện thao tác này.');return}
      button.disabled=true;
      try{const response=await fetch(url,{method:'POST',headers:{Accept:'application/json'},signal:AbortSignal.timeout(30000)});const result=await response.json();if(!response.ok)throw Error(result.message||'Không thực hiện được thao tác.');await onSuccess(result)}
      catch(error){notifyCustomer(error.message)}finally{button.disabled=false}
    });actions.prepend(button);
  };
  if(table==='sales_headers'&&row.document_type==='QUOTE') {
    if(!row.sales_order_id&&['DRAFT','RELEASED'].includes(row.status)) action(row.status==='DRAFT'?'Release':'Reopen',
      '/api/erp/sales-form/headers/'+headerId+'/release?reopen='+(row.status==='RELEASED'),async()=>{erpEditDialog.close();await renderErpData()});
    action('Make Invoice','/api/erp/sales-quotes/'+headerId+'/make-invoice',async result=>{erpEditDialog.close();await openErpTable('sales_headers','sales_invoice_id',result.id)});
  }
}

async function appendSavedSalesLines(form,headerId) {
  if(!headerId)return;
  const section=erpElement('details',null,'document-section sales-document');section.open=true;
  section.append(erpElement('summary','Sales Lines'));
  const table=erpElement('table'),head=erpElement('tr');
  const captions={item_no:'No.',description:'Description',unit_of_measure:'Unit of Measure',quantity:'Quantity',unit_price:'Unit Price (VND)',discount_percent:'Line Discount %',vat_percent:'VAT %'};
  Object.values(captions).forEach(c=>head.append(erpElement('th',c)));const thead=erpElement('thead');thead.append(head);table.append(thead);
  const body=erpElement('tbody');table.append(body);const scroll=erpElement('div',null,'sales-lines-scroll');scroll.append(table);section.append(scroll);
  form.querySelector('.document-content').append(section);
  try{
    const lines=await erpGet('/sales-quotes/'+encodeURIComponent(headerId)+'/lines');
    lines.forEach(line=>{const row=erpElement('tr');Object.keys(captions).forEach(key=>row.append(erpElement('td',erpValue(line[key]??(key==='item_no'?line.item_id:''),key))));body.append(row)});
    if(!lines.length)section.append(erpElement('p','Chưa có dòng vật tư.','document-hint'));
  }catch(error){section.append(erpElement('p','Không tải được Sales Lines: '+error.message,'document-hint'))}
}

function arrangeSalesForm(form) {
  const content=form.querySelector('.document-content');if(!content)return;
  const sections=[...form.querySelectorAll('.document-section')];
  const find=name=>sections.find(s=>s.querySelector('summary')?.firstChild?.textContent===name);
  const general=find('General')?.querySelector('.document-fields');
  if(general) {
    const fields=new Map([...general.children].map(label=>[label.querySelector('[name]')?.name,label]));
    const left=['quote_no','order_no','return_no','customer_id','sell_to_name','external_document_no','sell_to_address','sell_to_address_2','sell_to_city','sell_to_state','sell_to_post_code','sell_to_country_code','sell_to_contact_no','sell_to_phone','sell_to_mobile','sell_to_email','sell_to_contact','customer_template_code'];
    const right=['archived_versions','order_date','document_date','quote_valid_until_date','posting_date','vat_date','due_date','requested_delivery_date','promised_delivery_date','your_reference','salesperson_code','campaign_no','opportunity_no','responsibility_center','assigned_user_id','status'];
    const columns=[left,right].map(names=>names.filter(name=>fields.has(name)));
    for(let i=0;i<Math.max(...columns.map(c=>c.length));i++)for(const column of columns){const name=column[i];if(name){general.append(fields.get(name));fields.delete(name)}}
    fields.forEach(field=>general.append(field));
  }
  const lines=form.querySelector('.sales-document')||form.querySelector('[data-sales-source-lines]');
  for(const name of ['General','Lines & Totals','Invoice Details','Shipping and Billing','System Information']) {
    const section=find(name);if(section)content.append(section);
    if(name==='General'&&lines)content.append(lines);
  }
  form.classList.add('bc-sales-form');
  form.querySelector('.document-breadcrumb').textContent='Sales / Chứng từ bán hàng';
}
