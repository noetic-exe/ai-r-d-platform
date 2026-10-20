package com.aird;

import org.apache.commons.csv.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

@Component
public class FileStorage {
    final Path root;
    FileStorage(@Value("${app.storage}") String storage) throws IOException { root=Path.of(storage).toAbsolutePath().normalize(); Files.createDirectories(root); }
    record Table(List<String> headers,List<String[]> rows) {}
    Path path(String key) { Problem.require(key.matches("[a-f0-9-]+\\.(csv|json)"),400,"Invalid object key."); return root.resolve(key); }
    Path receive(MultipartFile file) throws IOException {
        Problem.require(!file.isEmpty() && file.getSize()<=20L*1024*1024,422,"Choose a nonempty CSV no larger than 20 MB.");
        Path temp=Files.createTempFile(root,"upload-",".tmp");
        try(var in=file.getInputStream(); var out=Files.newOutputStream(temp)) {
            byte[] buffer=new byte[8192]; int n;long size=0;
            while((n=in.read(buffer))!=-1) { size+=n; Problem.require(size<=20L*1024*1024,413,"CSV exceeds 20 MB.");out.write(buffer,0,n); }
        } catch(Exception e) { Files.deleteIfExists(temp);throw e; } return temp;
    }
    Table read(Path path) {
        try(var reader=new InputStreamReader(Files.newInputStream(path),StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT));
            var parser=CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(false).setAllowMissingColumnNames(false).get().parse(reader)) {
            var headers=new ArrayList<>(parser.getHeaderNames());
            if(!headers.isEmpty() && headers.getFirst().startsWith("\uFEFF")) headers.set(0,headers.getFirst().substring(1));
            Problem.require(headers.size()>=2 && headers.size()<=100,422,"CSV needs 2–100 columns.");
            Problem.require(new HashSet<>(headers).size()==headers.size() && headers.stream().allMatch(h->!h.isBlank() && h.length()<=100),422,"Column names must be unique, nonempty, and at most 100 characters.");
            var rows=new ArrayList<String[]>();
            for(var record:parser) {
                Problem.require(record.size()==headers.size(),422,"Row "+record.getRecordNumber()+" has the wrong number of columns.");
                Problem.require(rows.size()<50000,422,"CSV exceeds the 50,000-row limit.");
                String[] row=new String[headers.size()];for(int i=0;i<row.length;i++) { row[i]=record.get(i).trim(); Problem.require(row[i].length()<=10000 && row[i].indexOf('\0')<0,422,"A cell is invalid or exceeds 10,000 characters."); } rows.add(row);
            }
            Problem.require(!rows.isEmpty(),422,"CSV contains no data rows."); return new Table(headers,rows);
        } catch(Problem e) {throw e;} catch(Exception e) {throw new Problem(422,"Malformed CSV. Use UTF-8, a header row, and consistent comma-separated columns.");}
    }
    Map<String,Object> summary(Table t) {
        var columns=new ArrayList<Map<String,Object>>();
        for(int c=0;c<t.headers().size();c++) {
            int missing=0;boolean numeric=true;
            for(var row:t.rows()) {if(row[c].isBlank()) missing++;else try {numeric &= Double.isFinite(Double.parseDouble(row[c]));}catch(NumberFormatException e){numeric=false;} }
            columns.add(Map.of("name",t.headers().get(c),"type",numeric?"numeric":"categorical","missing",missing));
        }
        return Map.of("columns",columns,"preview",t.rows().stream().limit(8).toList());
    }
    String checksum(Path p) throws IOException {
        try(var in=Files.newInputStream(p)) { var md=MessageDigest.getInstance("SHA-256");byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)md.update(b,0,n);return HexFormat.of().formatHex(md.digest()); } catch(NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }
    void publish(Path temp,String key) throws IOException {
        Files.move(temp,path(key),StandardCopyOption.ATOMIC_MOVE);
        if(TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {if(status!=STATUS_COMMITTED)try {Files.deleteIfExists(path(key));}catch(IOException ignored){}}
        });
    }
    void deleteAfterCommit(String key) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {try {Files.deleteIfExists(path(key));}catch(IOException ignored){}}
        });
    }
}
