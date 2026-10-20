package com.aird;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.context.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:h2:mem:integration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","app.demo=true","debug=false","logging.level.root=ERROR"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlatformIntegrationTest {
 @Autowired Environment env;@Autowired Store s;@Autowired Worker worker;
 @Autowired org.springframework.security.crypto.password.PasswordEncoder passwords;
 static final JsonMapper json=JsonMapper.builder().build();
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r)throws Exception{Files.createDirectories(Path.of("target/test-storage"));var p=Files.createTempDirectory(Path.of("target/test-storage"),"integration-");r.add("app.storage",()->p.toAbsolutePath().toString());}
 class Client {
  final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();String csrf;
  URI uri(String p){return URI.create("http://127.0.0.1:"+env.getProperty("local.server.port")+"/api"+p);}
  HttpResponse<String> send(String method,String path,Object body)throws Exception{
   var b=HttpRequest.newBuilder(uri(path));if(!method.equals("GET"))b.header("X-CSRF-TOKEN",Objects.requireNonNullElse(csrf,"")).header("Content-Type","application/json");
   return http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
  }
  void token()throws Exception{csrf=map(send("GET","/auth/csrf",null)).get("token").toString();}
  void login(String email,String password)throws Exception{token();var r=send("POST","/auth/login",Map.of("email",email,"password",password));assertEquals(200,r.statusCode(),r.body());token();}
  HttpResponse<String> upload(long id,String csv,String name)throws Exception{
   String boundary="testBoundary",body="--"+boundary+"\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\n"+name+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"test.csv\"\r\nContent-Type: text/csv\r\n\r\n"+csv+"\r\n--"+boundary+"--\r\n";
   return http.send(HttpRequest.newBuilder(uri("/projects/"+id+"/datasets")).header("X-CSRF-TOKEN",csrf).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
  }
 }
 @SuppressWarnings("unchecked") Map<String,Object> map(HttpResponse<String> r){return json.readValue(r.body(),Map.class);}
 Client admin()throws Exception{var c=new Client();c.login("admin@aird.local","AdminDemo!2026");return c;}
 Client maya()throws Exception{var c=new Client();c.login("maya@aird.local","ResearchDemo!2026");return c;}
 Client jordan()throws Exception{var c=new Client();c.login("jordan@aird.local","ResearchDemo!2026");return c;}
 long project(Client c,String name)throws Exception{var r=c.send("POST","/projects",Map.of("name",name,"description","Test project","storage_quota_mb",10,"runtime_quota_seconds",36000));assertEquals(200,r.statusCode(),r.body());long id=((Number)map(r).get("id")).longValue();assertEquals(200,c.send("POST","/projects/"+id+"/members",Map.of("user_id",2)).statusCode());return id;}
 String csv(){var b=new StringBuilder("x,group,target\n");for(int i=0;i<80;i++)b.append(i).append(',').append(i<40?"a,no":"b,yes").append('\n');return b.toString();}
 Map<String,Object> run(long v,String key){return Map.of("name","Integration training","dataset_version_id",v,"target_column","target","model","logistic_regression","parameters",Map.of("epochs",30),"seed",42,"test_fraction",.2,"submission_key",key);}
 Map<String,Object> waitFor(Client c,long id)throws Exception{for(int i=0;i<300;i++){var j=map(c.send("GET","/training-jobs/"+id,null));if(!Set.of("queued","running").contains(j.get("state")))return j;Thread.sleep(100);}fail("Training timed out.");return Map.of();}
 @Test @Order(1) void authorizationCsrfAndLogout()throws Exception{
  assertEquals(401,new Client().send("GET","/projects",null).statusCode());var c=maya();assertEquals(403,c.send("GET","/users",null).statusCode());assertEquals(404,c.send("GET","/projects/2/datasets",null).statusCode());
  c.csrf="wrong";assertEquals(403,c.send("POST","/auth/logout",Map.of()).statusCode());c.token();assertEquals(200,c.send("POST","/auth/logout",Map.of()).statusCode());assertEquals(401,c.send("GET","/auth/me",null).statusCode());
 }
 @Test @Order(2) void duplicatesAndLastAdminProtected()throws Exception{
  var c=admin();assertEquals(409,c.send("POST","/users",Map.of("name","Duplicate","email","MAYA@aird.local","role","researcher","active",true,"password","Temporary!2026")).statusCode());assertEquals(409,c.send("DELETE","/users/1",Map.of()).statusCode());
  String hash=s.one("SELECT password_hash FROM users WHERE id=1").get("password_hash").toString();assertTrue(hash.startsWith("$argon2"));assertTrue(passwords.matches("AdminDemo!2026",hash));
 }
 @Test @Order(3) void csvValidationPreviewAndDeletion()throws Exception{
  var c=maya();assertEquals(422,c.upload(1,"x,target\n","empty").statusCode());assertEquals(422,c.upload(1,"x,target\n1,a,extra\n","bad").statusCode());assertEquals(422,c.upload(1,"x,x\n1,2\n","duplicate").statusCode());
  var r=c.upload(1,csv(),"Valid fixture");assertEquals(200,r.statusCode(),r.body());long v=((Number)map(r).get("version_id")).longValue();var preview=map(c.send("GET","/dataset-versions/"+v+"/preview",null));assertEquals(80,((Number)preview.get("rows")).intValue());assertEquals(64,preview.get("checksum").toString().length());
  assertEquals(200,c.send("GET","/dataset-versions/"+v+"/download",null).statusCode());assertEquals(200,c.send("DELETE","/dataset-versions/"+v,Map.of()).statusCode());assertEquals(404,c.send("GET","/dataset-versions/"+v+"/preview",null).statusCode());
 }
 @Test @Order(4) void realTrainingIdempotencyArtifactsAndComparison()throws Exception{
  var ad=admin();long p=project(ad,"Training workflow");var c=maya();var up=map(c.upload(p,csv(),"Train fixture"));long v=((Number)up.get("version_id")).longValue();var body=run(v,"same-submit");var r=c.send("POST","/projects/"+p+"/training-jobs",body);assertEquals(202,r.statusCode(),r.body());long id=((Number)map(r).get("id")).longValue();
  assertEquals(id,((Number)map(c.send("POST","/projects/"+p+"/training-jobs",body)).get("id")).longValue());var j=waitFor(c,id);assertEquals("completed",j.get("state"),j.toString());assertTrue(((Number)((Map<?,?>)j.get("metrics")).get("accuracy")).doubleValue()>.9);
  var artifact=c.send("GET","/training-jobs/"+id+"/artifact",null);assertEquals(200,artifact.statusCode());assertEquals("aird-java-model-v1",map(artifact).get("format"));assertNotNull(map(artifact).get("dataset_checksum"));
  long second=((Number)map(c.send("POST","/projects/"+p+"/training-jobs",run(v,"second-submit"))).get("id")).longValue();assertEquals(j.get("metrics"),waitFor(c,second).get("metrics"));
  assertEquals(true,map(c.send("GET","/experiments/compare?jobs="+id+","+second,null)).get("compatible"));assertEquals(200,c.send("GET","/experiments/export.csv?jobs="+id+","+second,null).statusCode());
  assertEquals(200,c.send("DELETE","/dataset-versions/"+v,Map.of()).statusCode());assertEquals(true,s.one("SELECT archived FROM dataset_versions WHERE id=?",v).get("archived"));assertEquals(409,ad.send("DELETE","/resources/1",Map.of()).statusCode());
 }
 @Test @Order(5) void collaborationApprovalAndImmediateRevocation()throws Exception{
  var ad=admin();long p=project(ad,"Collaboration");var c=maya();var other=jordan();long v=((Number)map(c.upload(p,csv(),"Private fixture")).get("version_id")).longValue();long request=((Number)map(c.send("POST","/projects/"+p+"/collaboration-requests",Map.of("user_id",3))).get("id")).longValue();
  assertEquals(404,other.send("GET","/dataset-versions/"+v+"/download",null).statusCode());assertEquals(200,ad.send("POST","/collaboration-requests/"+request+"/approve",Map.of()).statusCode());assertEquals(200,other.send("GET","/dataset-versions/"+v+"/download",null).statusCode());
  assertEquals(200,other.send("POST","/projects/"+p+"/comments",Map.of("body","Shared note")).statusCode());assertEquals(200,ad.send("DELETE","/projects/"+p+"/members/3",Map.of()).statusCode());assertEquals(404,other.send("GET","/dataset-versions/"+v+"/download",null).statusCode());
 }
 @Test @Order(6) void quotasAndModelValidation()throws Exception{
  var ad=admin();long p=project(ad,"Quotas");var c=maya();long v=((Number)map(c.upload(p,csv(),"Quota fixture")).get("version_id")).longValue();var body=new HashMap<>(run(v,"invalid"));body.put("model","arbitrary_code");assertEquals(422,c.send("POST","/projects/"+p+"/training-jobs",body).statusCode());
  body.put("model","random_forest");body.put("parameters",Map.of("n_estimators",100000));assertEquals(422,c.send("POST","/projects/"+p+"/training-jobs",body).statusCode());s.db.update("UPDATE projects SET runtime_quota_seconds=0 WHERE id=?",p);assertEquals(409,c.send("POST","/projects/"+p+"/training-jobs",run(v,"quota")).statusCode());s.db.update("UPDATE projects SET storage_quota_mb=0 WHERE id=?",p);assertEquals(409,c.upload(p,csv(),"Over storage").statusCode());
 }
 @Test @Order(7) void lostWorkerAndUsage()throws Exception{
  var ad=admin();var c=maya();var j=s.one("SELECT * FROM jobs WHERE state='completed' LIMIT 1");
  long id=s.insert("INSERT INTO jobs(project_id,user_id,experiment_id,dataset_version_id,name,model,target_column,parameters_json,seed,test_fraction,submission_key,state,stage,lease_until,created_at,started_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,'running','training',0,?,?)",j.get("project_id"),2,j.get("experiment_id"),j.get("dataset_version_id"),"Lost worker","baseline","target","{}",42,.2,"lost-worker",Store.now(),java.time.Instant.now().minusSeconds(2).toString());
  worker.reconcile();assertEquals("failed",map(c.send("GET","/training-jobs/"+id,null)).get("state"));assertTrue(s.count("SELECT runtime_ms FROM jobs WHERE id=?",id)>=2000);assertEquals(200,ad.send("GET","/usage/export.csv",null).statusCode());assertEquals(200,ad.send("GET","/audit",null).statusCode());
 }
 @Test @Order(8) void profileCannotEscalateAndRevokesOtherSessions()throws Exception{
  var c=maya();var r=c.send("PATCH","/profile",Map.of("name","Maya Chen","email","maya@aird.local","role","admin"));assertEquals(200,r.statusCode(),r.body());assertEquals("researcher",map(c.send("GET","/auth/me",null)).get("role"));var second=maya();
  var change=c.send("PATCH","/profile",Map.of("name","Maya Chen","email","maya.changed@aird.local","current_password","ResearchDemo!2026"));assertEquals(200,change.statusCode(),change.body());assertEquals(401,second.send("GET","/auth/me",null).statusCode());assertEquals(401,c.send("GET","/auth/me",null).statusCode());
 }

 @Test @Order(7) void queuedCancellationAndConcurrentQuotaReservations()throws Exception{
  var ad=admin();long p=project(ad,"Concurrent reservations");var c=maya();long v=((Number)map(c.upload(p,csv(),"Concurrent fixture")).get("version_id")).longValue();
  s.db.update("UPDATE projects SET runtime_quota_seconds=600 WHERE id=?",p);
  s.db.update("UPDATE worker_slot SET owner='test-hold',lease_until=? WHERE id=1",System.currentTimeMillis()+60000);
  try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
   var start=new java.util.concurrent.CountDownLatch(1);
   java.util.concurrent.Callable<HttpResponse<String>> one=()->{start.await();return c.send("POST","/projects/"+p+"/training-jobs",run(v,"race-a"));};
   java.util.concurrent.Callable<HttpResponse<String>> two=()->{start.await();return c.send("POST","/projects/"+p+"/training-jobs",run(v,"race-b"));};
   var first=pool.submit(one);var second=pool.submit(two);start.countDown();var r1=first.get();var r2=second.get();
   assertEquals(Set.of(202,409),Set.of(r1.statusCode(),r2.statusCode()));
   long id=((Number)map(r1.statusCode()==202?r1:r2).get("id")).longValue();
   assertEquals(409,c.send("DELETE","/dataset-versions/"+v,Map.of()).statusCode());
   assertEquals(200,c.send("POST","/training-jobs/"+id+"/cancel",Map.of()).statusCode());assertEquals("cancelled",map(c.send("GET","/training-jobs/"+id,null)).get("state"));
  }finally{s.db.update("UPDATE worker_slot SET owner=NULL,lease_until=0 WHERE id=1 AND owner='test-hold'");}
 }
 @Test @Order(7) void unsuitableTrainingFailsWithLogs()throws Exception{
  var ad=admin();long p=project(ad,"Failed training");var c=maya();
  long v=((Number)map(c.upload(p,"x,target\n1,a\n2,a\n3,a\n4,a\n5,a\n6,a\n7,a\n8,a\n9,a\n10,a\n","Single class fixture")).get("version_id")).longValue();
  var r=c.send("POST","/projects/"+p+"/training-jobs",run(v,"failure"));assertEquals(202,r.statusCode(),r.body());long id=((Number)map(r).get("id")).longValue();var j=waitFor(c,id);
  assertEquals("failed",j.get("state"));assertTrue(j.get("error").toString().contains("classes"));assertFalse(((List<?>)j.get("events")).isEmpty());assertEquals(409,c.send("GET","/training-jobs/"+id+"/artifact",null).statusCode());
 }
 @Test @Order(9) void databaseAndArtifactBackupRestore()throws Exception{
  var script=Path.of("target","restore-"+UUID.randomUUID()+".sql").toAbsolutePath();s.db.execute("SCRIPT TO '"+script.toString().replace("'","''")+"'");
  try(var connection=java.sql.DriverManager.getConnection("jdbc:h2:mem:restored;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE","sa","");var statement=connection.createStatement()){
   statement.execute("RUNSCRIPT FROM '"+script.toString().replace("'","''")+"'");
   try(var rows=statement.executeQuery("SELECT COUNT(*) FROM jobs")){rows.next();assertEquals(s.count("SELECT COUNT(*) FROM jobs"),rows.getLong(1));}
   try(var rows=statement.executeQuery("SELECT artifact_key,artifact_checksum FROM jobs WHERE state='completed' LIMIT 1")){
    assertTrue(rows.next());var original=Path.of(env.getProperty("app.storage")).resolve(rows.getString(1));var copy=script.getParent().resolve("restored-"+UUID.randomUUID()+".json");Files.copy(original,copy);
    var digest=java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(copy));assertEquals(rows.getString(2),HexFormat.of().formatHex(digest));
   }
  }
 }
}