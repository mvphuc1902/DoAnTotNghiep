// Downloads use the exact saved preview DOM/dataset, never a second database query.
async function mountReportDownloads(win, options = {}) {
  const doc=win.document;if(!doc.querySelector)return;
  const sheet=doc.querySelector('.sheet');if(!sheet)return;
  const name=(options.name||doc.title||'Bao-cao').replace(/[\\/:*?"<>|\x00-\x1f]/g,'-').slice(0,120);
  const toolbar=doc.querySelector('.toolbar')||doc.body.insertBefore(doc.createElement('div'),doc.body.firstChild);toolbar.className='toolbar';toolbar.replaceChildren();
  const label=doc.createElement('span');label.textContent='Xem trước · '+(options.period||'Dữ liệu đã chọn');label.style.marginRight='auto';toolbar.append(label);
  const error=doc.createElement('span');error.setAttribute('role','alert');error.style.color='#b91c1c';toolbar.append(error);
  const style=doc.createElement('style');style.textContent='.sheet{min-width:0!important;max-width:none!important}td,th{overflow-wrap:anywhere} @media print{.toolbar{display:none}}';doc.head.append(style);
  const loads=new Map();
  function load(src){if(!loads.has(src))loads.set(src,new Promise((resolve,reject)=>{const script=doc.createElement('script');script.src=location.origin+src;script.onload=resolve;script.onerror=()=>{loads.delete(src);reject(Error('Không tải được thư viện xuất file. Hãy đăng nhập lại và mở Report.'));};doc.head.append(script);}));return loads.get(src);}
  function button(text,fn){const b=doc.createElement('button');b.type='button';b.textContent=text;b.onclick=async()=>{b.disabled=true;error.textContent='';try{await fn();}catch(e){error.textContent=e.message||'Không tải được báo cáo.';}finally{b.disabled=false;}};toolbar.append(b);return b;}
  function download(blob,filename){const url=URL.createObjectURL(blob),link=document.createElement('a');link.href=url;link.download=filename;document.body.append(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),60000);}
  const landscape=!!options.landscape;
  button('Tải PDF',async()=>{
    await load('/vendor/html2pdf-0.13.0.min.js');await doc.fonts.ready;
    const clone=sheet.cloneNode(true);clone.style.cssText='width:'+(landscape?'1100':'760')+'px;min-width:0;max-width:none;margin:0;padding:20px;box-shadow:none;min-height:0';
    clone.querySelectorAll('table').forEach(t=>{t.style.minWidth='0';t.style.width='100%';});clone.querySelectorAll('.table-wrap').forEach(t=>t.style.overflow='visible');
    const pdf=await win.html2pdf().set({filename:name+'.pdf',margin:8,image:{type:'jpeg',quality:0.98},html2canvas:{scale:1.5,backgroundColor:'#ffffff',scrollX:0,scrollY:0},jsPDF:{unit:'mm',format:'a4',orientation:landscape?'landscape':'portrait'},pagebreak:{mode:['css','legacy'],avoid:['tr','.summary','.address']}}).from(clone).outputPdf('blob');
    download(pdf,name+'.pdf');
  });
  if(options.excel!==false)button('Tải Excel',async()=>{
    await load('/vendor/xlsx-0.20.3.min.js');const X=win.XLSX,book=X.utils.book_new();
    const info=X.utils.aoa_to_sheet([['Báo cáo',doc.title],['Phạm vi',options.period||'Bản ghi đang mở'],['Ngày xuất',new Date().toLocaleString('vi-VN')]]);X.utils.book_append_sheet(book,info,'Thong tin');
    if(options.columns&&options.rows){
      const values=[options.columns.map(c=>options.labels?.[c]||c),...options.rows.map(r=>options.columns.map(c=>r[c]??''))];
      const ws=X.utils.aoa_to_sheet(values);ws['!autofilter']={ref:ws['!ref']||'A1'};X.utils.book_append_sheet(book,ws,'Du lieu');
    }else{
      [...sheet.querySelectorAll('table')].forEach((table,index)=>{const values=[...table.rows].map(row=>[...row.cells].map(cell=>{const raw=cell.textContent.trim();if(cell.classList.contains('number')&&/^-?[\d.]+(?:,\d+)?$/.test(raw))return Number(raw.replace(/\./g,'').replace(',','.'));return raw;}));X.utils.book_append_sheet(book,X.utils.aoa_to_sheet(values),'Du lieu '+(index+1));});
    }
    download(new Blob([X.write(book,{bookType:'xlsx',type:'array',compression:true})],{type:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'}),name+'.xlsx');
  });
  button('In',()=>win.print());button('Đóng',()=>win.close());
}
