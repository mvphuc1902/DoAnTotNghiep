function openOrderPlanning() {
  erpState.view = 'order-planning';
  switchTab('data');
  ++erpRequestVersion;
  const panel = document.getElementById('tab-data');
  const surface=erpElement('div',null,'planning-workspace');
  const title=erpElement('h2','Order Planning','text-xl font-bold');
  const toolbar=erpElement('div',null,'flex flex-wrap gap-3 items-center');
  const message=erpElement('p','Nhấn Calculate Plan để tính nhu cầu từ Sales Orders đã Release và Production Orders nháp có BOM.','document-hint');
  message.setAttribute('role','status');
  const content=erpElement('div',null,'overflow-auto');
  const results=erpElement('div',null,'flex flex-wrap gap-3');
  let lines=[],selected=new Set(),busy=false,requestKey=null;
  const calculate=erpButton('Calculate Plan',load);
  const make=erpButton('Make Orders',create);make.disabled=true;
  const refresh=erpButton('Refresh Planning Lines',load);
  toolbar.className='planning-actions';toolbar.append(calculate,make,refresh);
  const top=erpElement('header',null,'planning-top');
  top.append(erpButton('←',()=>openSalesDocuments('ORDER'),'planning-back'),title);
  const general=erpElement('section',null,'planning-general');general.append(erpElement('h3','General'));
  const demandLabel=erpElement('label','Show Demand as'),demand=erpElement('select');demand.setAttribute('aria-label','Show Demand as');
  [['all','All Demands'],['shortage','Thiếu tồn kho'],['purchase','Có thể tạo Purchase Order']].forEach(([value,label])=>{const option=erpElement('option',label);option.value=value;demand.append(option);});
  demandLabel.append(demand);general.append(demandLabel);demand.onchange=load;
  const tabs=erpElement('div',null,'planning-tabs');tabs.append(erpElement('span','Home','planning-tab-active'),erpButton('Item Availability',()=>summary.scrollIntoView({behavior:'smooth',block:'nearest'}),'planning-tab'));
  content.className='planning-grid';
  const summary=erpElement('section',null,'planning-availability');
  function showAvailability(line){summary.replaceChildren();[['Item No.',line?.item_no],['Location',line?.location_code],['Quantity Available',line?.available],['Incoming Purchase Qty.',line?.incoming],['Demand Quantity',line?.demand],['Quantity to Purchase',line?.suggested]].forEach(([label,value])=>{const field=erpElement('div');field.append(erpElement('span',label),erpElement('output',value==null?'—':String(value)));summary.append(field);});}
  showAvailability(null);
  surface.append(top,general,tabs,toolbar,message,content,summary,results);panel.replaceChildren(surface);
  const guide=erpElement('details',null,'document-section');
  guide.append(erpElement('summary','Cách sử dụng Order Planning'));
  const steps=erpElement('ol',null,'list-decimal pl-6 space-y-2');
  ['Mở Items, chọn Inventory, Replenishment System = Purchase và Vendor mua hàng, rồi lưu.',
   'Nhập tồn kho tại kho cần bán. Ví dụ: tồn khả dụng 30.',
   'Tạo Sales Order mới, chọn cùng kho và thêm Item với số lượng 100. Lưu rồi mở card, bấm Release.',
   'Với sản xuất: tạo Item sản xuất, Production Order nháp có kho/số lượng và BOM. Linh kiện cần mua phải chọn Purchase và Vendor; nhu cầu = định mức × số lượng sản xuất.',
   'Mở Order Planning: trang tự tính kế hoạch. Bấm Calculate Plan để cập nhật sau khi thay đổi dữ liệu.',
   'Kiểm tra số cần bổ sung. Tồn 30, nhu cầu 100, chưa có PO chờ nhập → đề xuất mua 70.',
   'Tick dòng đề xuất và bấm Make Orders. Bấm mã PO vừa tạo để xem Purchase Order.',
   'Release PO và nhận hàng để tăng tồn kho; tạo PO chưa làm tăng tồn.'].forEach(text=>steps.append(erpElement('li',text)));
  guide.append(steps);surface.append(guide);
  const diagnosticDetails=erpElement('details',null,'planning-source-details');diagnosticDetails.append(erpElement('summary','Chứng từ nguồn chưa vào kế hoạch'));const diagnostics=erpElement('section',null,'space-y-3');diagnosticDetails.append(diagnostics);surface.append(diagnosticDetails);
  async function showSources(){
    diagnostics.replaceChildren(erpElement('p','Đang kiểm tra chứng từ nguồn…'));
    try{
      const orders=[];
      for(let page=0;;page++){
        const data=await erpGet('/tables/sales_orders?'+new URLSearchParams({page,size:100}));
        orders.push(...data.rows);if(data.rows.length<100||orders.length>=Number(data.total))break;
      }
      const states=[];
      for(let offset=0;offset<orders.length;offset+=10){
        states.push(...await Promise.all(orders.slice(offset,offset+10).map(async order=>({order,state:await erpGet('/sales-shipping/'+order.id)}))));
        if(!title.isConnected)return;
      }
      diagnostics.replaceChildren(erpElement('h3','Sales Orders hiện có · Lý do chưa có đề xuất','font-semibold'));
      const table=erpElement('table',null,'min-w-full text-sm text-left'),head=erpElement('tr');
      ['Sales Order','Số dòng','Tình trạng','Thao tác'].forEach(text=>head.append(erpElement('th',text,'px-4 py-3')));const thead=erpElement('thead');thead.append(head);table.append(thead);const body=erpElement('tbody');table.append(body);
      states.forEach(({order,state})=>{
        const reason=!state.deferred?'Đơn luồng cũ: tạo Sales Order mới để dùng Planning.'
          :!order.location_id?'Chưa chọn kho.':!state.lines.length?'Chưa có dòng hàng.':!state.released?'Chưa Release.':'Không còn nhu cầu Inventory đủ điều kiện (kiểm tra lượng đã giao, loại Item và trạng thái khóa).';
        const tr=erpElement('tr');[order.order_no,state.lines.length,reason].forEach(value=>tr.append(erpElement('td',String(value),'px-4 py-3')));
        const td=erpElement('td');td.append(erpButton('Xem',()=>showErpDetail('sales_orders',order,Object.keys(order))));tr.append(td);body.append(tr);
      });
      diagnostics.append(table,erpButton('Mở Items',()=>openErpTable('items')),erpButton('Mở Sales Orders',()=>openSalesDocuments('ORDER')));
      if(!orders.length)diagnostics.prepend(erpElement('p','Chưa có Sales Order. Tạo đơn bán mới theo hướng dẫn phía trên.'));
    }catch(e){if(title.isConnected)diagnostics.replaceChildren(erpElement('p','Không tải được chứng từ nguồn: '+e.message));}
  }
  function sync(){make.disabled=busy||!selected.size;calculate.disabled=busy;refresh.disabled=busy;demand.disabled=busy;content.querySelectorAll('input').forEach(input=>input.disabled=busy||input.dataset.eligible!=='true');}
  async function load(){
    if(busy)return;busy=true;selected.clear();requestKey=null;showAvailability(null);sync();message.textContent='Đang tính kế hoạch…';
    try{
      lines=await erpGet('/order-planning');if(!title.isConnected)return;
      const table=erpElement('table',null,'min-w-full text-sm text-left whitespace-nowrap');
      const head=erpElement('tr');['Chọn','Demand Type','Order No.','Supply From','Item No.','Description','Unit of Measure Code','Location','Demand Qty.','Inventory','Reserved Qty.','Quantity Available','Incoming Qty.','Quantity to Purchase','Status'].forEach(s=>head.append(erpElement('th',s,'px-4 py-3')));
      const thead=erpElement('thead');thead.append(head);table.append(thead);const body=erpElement('tbody');table.append(body);
      let visibleCount=0;
      lines.forEach((line,index)=>{
        if(demand.value==='shortage'&&Number(line.suggested)<=0)return;
        if(demand.value==='purchase'&&(!line.can_purchase||Number(line.suggested)<=0))return;
        visibleCount++;
        const tr=erpElement('tr'),td=erpElement('td'),check=erpElement('input');check.type='checkbox';
        const eligible=line.can_purchase&&Number(line.suggested)>0;check.dataset.eligible=String(eligible);check.disabled=!eligible;
        check.setAttribute('aria-label','Mua '+line.item_no+' tại '+line.location_code);
        tr.tabIndex=0;const focusLine=()=>{body.querySelectorAll('tr').forEach(row=>row.classList.remove('planning-selected'));tr.classList.add('planning-selected');showAvailability(line);};tr.onclick=focusLine;tr.onkeydown=e=>{if(e.target===tr&&['Enter',' '].includes(e.key)){e.preventDefault();focusLine();}};
        check.onchange=()=>{focusLine();check.checked?selected.add(index):selected.delete(index);requestKey=null;sync();};td.append(check);tr.append(td);
        const status=Number(line.suggested)<=0?'Đã đủ tồn/đơn mua':line.replenishment_system!=='PURCHASE'?'Chọn Replenishment System = Purchase':!line.vendor_id||line.vendor_blocked?'Chọn Vendor còn hoạt động':'Đề xuất mua';
        [line.demand_type || 'Sales',line.sales_orders,line.vendor_name||'—',line.item_no,line.description,line.base_unit_of_measure || '—',line.location_code+' — '+line.location_name,...['demand','on_hand','reserved','available','incoming','suggested'].map(k=>Number(line[k]).toLocaleString('vi-VN',{maximumFractionDigits:3})),status].forEach(v=>tr.append(erpElement('td',v,'px-4 py-3')));body.append(tr);
      });
      if(!visibleCount){const empty=erpElement('tr'),cell=erpElement('td','Không có nhu cầu phù hợp. Kiểm tra chứng từ nguồn hoặc đổi Show Demand as.','planning-empty');cell.colSpan=15;empty.append(cell);body.append(empty);}
      content.replaceChildren(table);diagnostics.replaceChildren();diagnosticDetails.hidden=!!lines.length;guide.open=false;if(!lines.length)await showSources();message.textContent=lines.length?'Tick dòng cần mua rồi nhấn Make Orders.':'Không có nhu cầu Inventory chưa giao từ Sales Orders đã Release và Production Orders nháp có BOM.';
    }catch(e){message.textContent=e.message;}finally{busy=false;sync();}
  }
  async function create(){
    if(busy||!selected.size)return;busy=true;sync();requestKey ||= crypto.randomUUID();
    try{
      const response=await fetch(erpApi+'/order-planning/make-orders',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({requestKey,lines:[...selected].map(index=>({itemId:lines[index].item_id,locationId:lines[index].location_id,quantity:lines[index].suggested}))}),signal:AbortSignal.timeout(30000)});
      const data=await response.json().catch(()=>({}));if(!response.ok)throw Error(data.message||'Không tạo được PO. Hãy Calculate Plan lại.');
      results.replaceChildren(erpElement('strong','Đã tạo Purchase Orders:'));
      data.orders.forEach(order=>results.append(erpButton(order.document_no,()=>openErpTable('purchase_headers','id',order.id))));
      erpCatalog=null;busy=false;await load();
    }catch(e){message.textContent=e.name==='TimeoutError'?'Chưa nhận được kết quả. Bấm Make Orders lại để tra cùng yêu cầu, tránh tạo trùng.':e.message;}
    finally{busy=false;sync();}
  }
  load();
}
