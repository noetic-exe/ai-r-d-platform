async function action(name,id){
 switch(name){
 case 'reload':return location.reload();
 case 'menu':$('#sidebar').classList.toggle('open');return;
 case 'profile':location.hash='profile';return;
 case 'team':location.hash='team';return;
 case 'demo-admin':return demoLogin('admin@aird.local','AdminDemo!2026');
 case 'demo-researcher':return demoLogin('maya@aird.local','ResearchDemo!2026');
 case 'close-modal':closeModal();return;
 case 'logout':await api('/auth/logout',{method:'POST'});S.user=null;S.query='';S.project='';closeModal();await token();renderLogin();return;
 case 'new-run':return newRun();
 case 'job':return showJob(+id);
 case 'rerun':return newRun(await api('/training-jobs/'+id));
 case 'cancel-job':await api('/training-jobs/'+id+'/cancel',{method:'POST'});toast('Cancellation requested.');await load(true);return showJob(+id);
 case 'annotations':{const j=S.jobs.find(j=>j.experiment_id===+id),groups=await api('/projects/'+j.project_id+'/experiments'),e=groups.find(e=>e.id===+id);formModal('Experiment annotations','annotations',field('name','Experiment name','text',e.name,'required maxlength="120"')+textarea('notes','Notes',e.notes)+field('tags','Tags (comma separated)','text',e.tags,'maxlength="500"'),id);return;}
 case 'compare':return compare();
 case 'export-runs':if(!S.selected.size)throw new Error('Select completed runs to export.');location.href='/api/experiments/export.csv?jobs='+[...S.selected].join(',');return;
 case 'previous-page':if(S.pagination>1)S.pagination--;render();return;
 case 'next-page':if(scopedJobs().length>S.pagination*25)S.pagination++;render();return;
 case 'new-project':S.resources=await api('/resources');return projectForm();
 case 'edit-project':S.resources=await api('/resources');return projectForm(+id);
 case 'project':return showProject(+id);
 case 'project-datasets':closeModal();S.project=id;if(S.page==='datasets')await load();else location.hash='datasets';return;
 case 'archive-project':return confirmAction('Archive this project?','Research history stays available. New uploads and training will be disabled.','archive-project',id);
 case 'confirm-archive-project':await api('/projects/'+id+'/archive',{method:'POST'});break;
 case 'remove-member':return confirmAction('Remove project access?','This researcher will lose access on their next request.','remove-member',id);
 case 'confirm-remove-member':{const[p,u]=id.split(':');await api('/projects/'+p+'/members/'+u,{method:'DELETE'});break;}
 case 'upload-dataset':return uploadDataset();
 case 'new-version':return uploadDataset(+id);
 case 'dataset':return showDataset(+id);
 case 'dataset-version':{const[d,v]=id.split(':').map(Number);return showDataset(d,v);}
 case 'delete-version':return confirmAction('Delete or archive this version?','Unused versions are deleted. Referenced versions are archived. Active training blocks deletion.','delete-version',id);
 case 'confirm-delete-version':{const[,v]=id.split(':');const r=await api('/dataset-versions/'+v,{method:'DELETE'});toast(r.message);closeModal();return load(true);}
 case 'edit-dataset':{const d=await api('/datasets/'+id);formModal('Dataset metadata','dataset-metadata',field('name','Name','text',d.name,'required maxlength="120"')+textarea('description','Description',d.description)+field('source','Source','text',d.source,'maxlength="1000"')+field('license','License','text',d.license,'maxlength="200"'),id);return;}
 case 'request-member':return requestMember();
 case 'approve-request':await api('/collaboration-requests/'+id+'/approve',{method:'POST'});break;
 case 'reject-request':await api('/collaboration-requests/'+id+'/reject',{method:'POST'});break;
 case 'new-user':return userForm();
 case 'edit-user':return userForm(+id);
 case 'new-resource':return resourceForm();
 case 'edit-resource':return resourceForm(+id);
 case 'delete-resource':return confirmAction('Delete this resource?','Only unused resources can be deleted. Allocated resources can be disabled in their settings.','delete-resource',id);
 case 'confirm-delete-resource':await api('/resources/'+id,{method:'DELETE'});break;
 default:return;
 }
 toast('Changes saved.');closeModal();await load(true);
}
function demoLogin(email,password){$('#f-email').value=email;$('#f-password').value=password;$('form[data-form="login"]').requestSubmit();}
document.addEventListener('click',e=>{
 const el=e.target.closest('[data-action]');if(!el)return;e.preventDefault();const disabled=el.disabled;el.disabled=true;
 Promise.resolve(action(el.dataset.action,el.dataset.id)).catch(e=>toast(e.message,true)).finally(()=>{if(el.isConnected)el.disabled=disabled;});
});
document.addEventListener('submit',async e=>{
 const form=e.target.closest('[data-form]');if(!form)return;e.preventDefault();const submit=$('[type=submit]',form),error=$('.form-error',form);if(error)error.textContent='';if(submit)submit.disabled=true;
 const v=Object.fromEntries(new FormData(form)),id=form.dataset.id,kind=form.dataset.form;
 try{
 switch(kind){
 case 'login':S.user=await api('/auth/login',{method:'POST',body:v});await token();S.query='';if(S.user.must_change_password)location.hash='profile';route();await load();return;
 case 'profile':{if(!v.password)delete v.password;if(!v.current_password)delete v.current_password;const r=await api('/profile',{method:'PATCH',body:v});toast(r.message);if(r.reauthenticate){S.user=null;await token();renderLogin();}else{S.user=await api('/auth/me');render();}return;}
 case 'new-run':{const body={name:v.name,dataset_version_id:+v.dataset_version_id,seed:+v.seed,test_fraction:+v.test_fraction,target_column:v.target_column,model:v.model,experiment_id:v.experiment_id?+v.experiment_id:null,parent_job_id:v.parent_job_id?+v.parent_job_id:null,parameters:{},submission_key:crypto.randomUUID()};for(const k of ['epochs','n_estimators','max_depth'])if(v[k])body.parameters[k]=+v[k];if(!body.dataset_version_id)throw new Error('Select a dataset before starting training.');const j=await api('/projects/'+v.project_id+'/training-jobs',{method:'POST',body});toast('Training job queued.');closeModal();await load(true);return showJob(j.id);}
 case 'upload-dataset':case 'new-version':{const data=new FormData(form);if(data.get('file').size>20*1024*1024)throw new Error('Choose a CSV no larger than 20 MB.');const path=kind==='new-version'?'/datasets/'+id+'/versions':'/projects/'+v.project_id+'/datasets';const r=await api(path,{method:'POST',body:data});toast(r.message);closeModal();await load(true);return showDataset(r.id,r.version_id);}
 case 'dataset-metadata':await api('/datasets/'+id,{method:'PATCH',body:v});toast('Metadata updated.');closeModal();await load(true);return showDataset(+id);
 case 'project':for(const k of ['storage_quota_mb','runtime_quota_seconds'])v[k]=+v[k];for(const k of ['compute_resource_id','storage_resource_id'])v[k]=v[k]?+v[k]:null;await api('/projects'+(id?'/'+id:''),{method:id?'PATCH':'POST',body:v});break;
 case 'user':v.active=v.active==='on';if(!v.password)delete v.password;await api('/users'+(id?'/'+id:''),{method:id?'PATCH':'POST',body:v});break;
 case 'resource':v.capacity=+v.capacity;v.active=v.active==='on';await api('/resources'+(id?'/'+id:''),{method:id?'PATCH':'POST',body:v});break;
 case 'request-member':await api('/projects/'+v.project_id+'/collaboration-requests',{method:'POST',body:{user_id:+v.user_id}});break;
 case 'add-member':await api('/projects/'+id+'/members',{method:'POST',body:{user_id:+v.user_id}});await load(true);return showProject(+id);
 case 'project-comment':await api('/projects/'+id+'/comments',{method:'POST',body:v});return showProject(+id);
 case 'experiment-comment':await api('/experiments/'+id+'/comments',{method:'POST',body:v});return showJob(+form.dataset.job);
 case 'annotations':await api('/experiments/'+id,{method:'PATCH',body:v});break;
 case 'usage-filter':if(v.from&&v.to&&v.from>v.to)throw new Error('Start date must precede the end date.');usageFilters={from:v.from,to:v.to,resource:v.resource};await load(true);return;
 }
 toast('Changes saved.');closeModal();await load(true);
 }catch(e){if(error)error.textContent=e.message;else toast(e.message,true);}finally{if(submit?.isConnected)submit.disabled=false;}
});
document.addEventListener('change',async e=>{
 try{const el=e.target;
 if(el.id==='project-scope'){S.project=el.value;S.pagination=1;S.selected.clear();await load(true);}
 if(el.id==='run-status'){S.status=el.value;S.pagination=1;render();}
 if(el.matches('.run-select')){el.checked?S.selected.add(+el.value):S.selected.delete(+el.value);const b=$('[data-action="compare"]');if(b)b.innerHTML=icon('compare')+'Compare selected ('+S.selected.size+')';}
 if(el.id==='select-all-runs'){$$('.run-select:not(:disabled)').forEach(c=>{c.checked=el.checked;c.checked?S.selected.add(+c.value):S.selected.delete(+c.value);});render();}
 if(el.id==='f-project_id'&&el.closest('[data-form="new-run"]'))await runDatasets(el.value);
 if(el.id==='f-dataset_version_id')await runTarget(el.value);
 if(el.id==='f-model')renderParams();
 }catch(e){toast(e.message,true);}
});
document.addEventListener('input',e=>{if(e.target.id==='global-search'){S.query=e.target.value;S.pagination=1;const pos=e.target.selectionStart;render();const el=$('#global-search');el?.focus();el?.setSelectionRange(pos,pos);}});
document.addEventListener('keydown',e=>{
 if((e.ctrlKey||e.metaKey)&&e.key.toLowerCase()==='k'){e.preventDefault();$('#global-search')?.focus();}
 if(e.key==='Escape')closeModal();
 if((e.key==='Enter'||e.key===' ')&&e.target.matches('.profile-nav')){e.preventDefault();location.hash='profile';}
 if(e.key==='Tab'&&S.modal){const nodes=$$('button:not(:disabled),a[href],input,select,textarea',$('.modal')),first=nodes[0],last=nodes.at(-1);if(e.shiftKey&&document.activeElement===first){e.preventDefault();last?.focus();}else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first?.focus();}}
});
window.addEventListener('hashchange',()=>{route();closeModal();if(S.user)load();});
setInterval(async()=>{
 if(polling||document.hidden||!S.user||S.loading||!S.jobs.some(j=>['queued','running'].includes(j.state)))return;
 polling=true;try{S.jobs=await api('/jobs');if(!S.modal)render();else{const live=$('#job-live');if(live){const job=S.jobs.find(j=>j.id===+live.dataset.job);if(job&&job.state!==$('span.badge',live)?.textContent.trim())await showJob(job.id);else if(job&&job.state==='running'){const full=await api('/training-jobs/'+job.id),logs=$('.logs',live);if(logs)logs.innerHTML=full.events.map(e=>'<span class="log-time">'+esc(new Date(e.created_at).toLocaleTimeString())+'</span> ['+esc(e.stage)+'] '+esc(e.message)).join('\n');}}}}catch(e){toast(e.message,true);}finally{polling=false;}
},3000);
async function start(){try{S.demo=(await api('/config')).demo;await token();try{S.user=await api('/auth/me');}catch{}route();if(S.user)await load();else renderLogin();}catch(e){$('#root').innerHTML='<div class="initial-loading"><p>'+esc(e.message)+'</p>'+btn('Try again','reload','arrow','secondary')+'</div>';}}
start();
