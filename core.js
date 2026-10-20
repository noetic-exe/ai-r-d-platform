const $=(s,r=document)=>r.querySelector(s),$$=(s,r=document)=>[...r.querySelectorAll(s)];
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const num=n=>Number(n||0).toLocaleString(),date=s=>s?new Date(s).toLocaleDateString(undefined,{month:'short',day:'numeric',year:'numeric'}):'—';
const bytes=n=>n<1024?num(n)+' B':n<1048576?(n/1024).toFixed(1)+' KB':(n/1048576).toFixed(2)+' MB';
const duration=ms=>ms<1000?num(ms)+' ms':ms<60000?(ms/1000).toFixed(1)+' s':(ms/60000).toFixed(1)+' min';
const modelName=s=>({logistic_regression:'Logistic regression',random_forest:'Random forest',linear_regression:'Linear regression',baseline:'Majority baseline'}[s]||s);
const paths={grid:'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z',folder:'M3 7V5h6l2 3h10v12H3z',database:'M20 6c0 2-4 3-8 3S4 8 4 6s4-3 8-3 8 1 8 3z M4 6v6c0 2 4 3 8 3s8-1 8-3V6 M4 12v6c0 2 4 3 8 3s8-1 8-3v-6',flask:'M9 3h6 M10 3v7L4 20h16l-6-10V3 M7 15h10',users:'M16 21v-3a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v3 M9 10a4 4 0 1 0 0-8 4 4 0 0 0 0 8 M17 3a4 4 0 0 1 0 8 M22 21v-3a4 4 0 0 0-3-4',cpu:'M5 5h14v14H5z M9 9h6v6H9z M9 2v3 M15 2v3 M9 19v3 M15 19v3 M2 9h3 M2 15h3 M19 9h3 M19 15h3',chart:'M3 3v18h18 M7 14l4-5 4 3 5-7',clock:'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18 M12 7v5l3 2',arrow:'M5 12h14 M14 7l5 5-5 5',plus:'M12 5v14 M5 12h14',search:'M10 3a7 7 0 1 0 0 14 7 7 0 0 0 0-14 M15 15l6 6',bell:'M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9 M10 21h4',chevron:'M9 5l7 7-7 7',file:'M5 3h9l5 5v13H5z M14 3v6h5 M8 13h8 M8 17h5',upload:'M12 16V3 M7 8l5-5 5 5 M3 15v6h18v-6',download:'M12 3v13 M7 11l5 5 5-5 M3 17v4h18v-4',check:'M5 12l4 4L19 6',close:'M6 6l12 12 M18 6 6 18',spark:'M12 3l2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5z',shield:'M12 3l8 3v6c0 5-8 9-8 9s-8-4-8-9V6z M8 12l3 3 5-6',logout:'M9 4H3v16h6 M10 12h11 M17 8l4 4-4 4',menu:'M3 6h18 M3 12h18 M3 18h18',compare:'M3 5h7v14H3z M14 5h7v14h-7z',edit:'M16 3l5 5-12 12-6 1 1-6z M13 6l5 5',stop:'M5 5h14v14H5z',message:'M3 3h18v14H7l-4 4z M7 8h10 M7 12h7'};
const icon=n=>'<svg class="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="'+(paths[n]||paths.grid)+'"/></svg>';
const badge=s=>'<span class="badge '+esc(s)+'"><i></i>'+esc(s)+'</span>';
const avatar=(name,dark=false)=>'<span class="avatar '+(dark?'dark':'')+'">'+esc(String(name).split(' ').map(w=>w[0]).slice(0,2).join('').toUpperCase())+'</span>';
const empty=(title,sub='',ic='flask')=>'<div class="empty">'+icon(ic)+'<strong>'+esc(title)+'</strong>'+esc(sub)+'</div>';
const btn=(text,action,ic='plus',kind='primary',id='')=>'<button type="button" class="button '+kind+'" data-action="'+action+'" data-id="'+id+'">'+icon(ic)+esc(text)+'</button>';
const art=()=>'<svg class="hero-art" viewBox="0 0 260 150" aria-hidden="true"><ellipse class="orbit" cx="134" cy="76" rx="112" ry="50" transform="rotate(-24 134 76)"/><ellipse class="orbit" cx="134" cy="76" rx="104" ry="45" transform="rotate(26 134 76)"/><path class="link" d="M55 47l79 29 76-40 M134 76l53 54 M134 76l-75 48 M134 76l-8-60"/><rect class="node" x="35" y="29" width="39" height="39" rx="12"/><rect class="node" x="191" y="17" width="39" height="39" rx="12"/><rect class="node" x="165" y="109" width="39" height="34" rx="10"/><rect class="node" x="39" y="109" width="36" height="34" rx="10"/><rect class="core" x="109" y="50" width="51" height="51" rx="16"/><path stroke="#fff" stroke-width="2" fill="none" d="M119 81l9-13 9 9 12-15 M121 87h25"/><circle fill="#b9abe8" cx="125" cy="16" r="4"/><path stroke="#b1a2d7" fill="none" stroke-width="1.5" d="M46 43h17v12H46z M201 32h18 M201 38h13 M177 121l4 7 9-13 M49 121h15 M49 126h10"/></svg>';
const S={user:null,demo:false,page:'overview',projects:[],jobs:[],datasets:[],researchers:[],requests:[],users:[],resources:[],audit:[],usage:[],project:'',query:'',status:'',selected:new Set(),pagination:1,modal:null,csrf:'',loading:false};
let polling=false,loadVersion=0,usageFilters={from:'',to:'',resource:''};
async function api(path,o={}){
 const method=o.method||'GET',headers={...o.headers};
 if(method!=='GET'){headers['X-CSRF-TOKEN']=S.csrf;if(!(o.body instanceof FormData)){headers['Content-Type']='application/json';if(o.body!==undefined)o.body=JSON.stringify(o.body);}}
 const r=await fetch('/api'+path,{...o,headers,credentials:'same-origin'}),d=await r.json().catch(()=>({}));
 if(!r.ok){if(r.status===401&&path!='/auth/login'){S.user=null;closeModal();renderLogin();}throw new Error(d.error?.message||'The request could not be completed.');}return d;
}
async function token(){S.csrf=(await api('/auth/csrf')).token;}
function toast(message,error=false){const el=document.createElement('div');el.className='toast'+(error?' error':'');el.textContent=message;$('#toasts').append(el);setTimeout(()=>el.remove(),5500);}
const activeProjects=()=>S.projects.filter(p=>p.status==='active');
const scopeProjects=()=>S.projects.filter(p=>!S.project||String(p.id)===S.project);
const scopedJobs=()=>S.jobs.filter(j=>(!S.project||String(j.project_id)===S.project)&&(!S.status||j.state===S.status)&&(!S.query||[j.name,j.model,j.dataset_name,j.project_name].join(' ').toLowerCase().includes(S.query.toLowerCase())));
function route(){const page=location.hash.slice(1)||'overview';S.page=['overview','projects','datasets','experiments','team','users','resources','usage','audit','profile'].includes(page)?page:'overview';S.pagination=1;S.status='';S.selected.clear();}
function usageQuery(){const p=new URLSearchParams();if(S.project)p.set('project',S.project);for(const[k,v]of Object.entries(usageFilters))if(v)p.set(k,v);return p.size?'?'+p:'';}
async function load(silent=false){
 const v=++loadVersion;if(!S.user)return;S.loading=true;if(!silent)render();
 try{
 if(S.user.must_change_password){S.page='profile';S.loading=false;render();return;}
 const results=await Promise.all([api('/projects'),api('/jobs'),api('/researchers'),api('/collaboration-requests')]);
 if(v!==loadVersion)return;[S.projects,S.jobs,S.researchers,S.requests]=results;
 if(S.project&&!S.projects.some(p=>String(p.id)===S.project))S.project='';
 if(S.page==='datasets')S.datasets=(await Promise.all(scopeProjects().map(p=>api('/projects/'+p.id+'/datasets')))).flat();
 if(S.user.role==='admin'){
 if(S.page==='users')S.users=await api('/users');
 if(S.page==='resources'||S.page==='projects')S.resources=await api('/resources');
 if(S.page==='audit')S.audit=await api('/audit');
 if(S.page==='usage'){S.resources=await api('/resources');S.usage=await api('/usage'+usageQuery());}
 }
 S.loading=false;render();
 }catch(e){S.loading=false;toast(e.message,true);if(S.user)render();}
}
