package com.aird;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class FileStorageTest {
 @TempDir Path root;
 @Test void quotedCsvAndMissingValuesArePreserved()throws Exception{
  var files=new FileStorage(root.toString());var p=root.resolve("test.csv");Files.writeString(p,"x,category,target\n1,\"red, blue\",yes\n,\"two\nlines\",no\n");var t=files.read(p);assertEquals(2,t.rows().size());assertEquals("red, blue",t.rows().getFirst()[1]);var cols=(List<?>)files.summary(t).get("columns");assertEquals(1,((Map<?,?>)cols.getFirst()).get("missing"));
 }
 @Test void rowAndColumnLimitsAreEnforced()throws Exception{
  var f=new FileStorage(root.toString());var p=root.resolve("test.csv");Files.writeString(p,"x,y\n"+"1,2\n".repeat(50001));assertThrows(Problem.class,()->f.read(p));var names=new ArrayList<String>();for(int i=0;i<101;i++)names.add("x"+i);Files.writeString(p,String.join(",",names)+"\n"+"1,".repeat(100)+"1\n");assertThrows(Problem.class,()->f.read(p));
 }
 @Test void malformedUtf8AndUnknownPathsAreRejected()throws Exception{
  var f=new FileStorage(root.toString());var p=root.resolve("test.csv");Files.write(p,new byte[]{(byte)0xC3,(byte)0x28});assertThrows(Problem.class,()->f.read(p));assertThrows(Problem.class,()->f.path("../../secret"));assertThrows(Problem.class,()->f.path("user-provided.csv"));
 }
 @Test void oversizedUploadIsRejectedBeforeSaving()throws Exception{
  var f=new FileStorage(root.toString());assertThrows(Problem.class,()->f.receive(new MockMultipartFile("file","large.csv","text/csv",new byte[20*1024*1024+1])));try(var stream=Files.list(root)){assertEquals(0,stream.count());}
 }
 @Test void exportedCellsCannotBecomeSpreadsheetFormulas(){
  var r=Reports.csv("test.csv",List.of(List.of("=HYPERLINK(\"x\")","normal","@formula","-bad")));assertTrue(r.getBody().contains("\"'=HYPERLINK(\"\"x\"\")\""));assertTrue(r.getBody().contains("\"'@formula\""));assertTrue(r.getBody().contains("\"normal\""));
 }
}
