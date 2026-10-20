package com.aird;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;

public class Problem extends RuntimeException {
    final int status;
    public Problem(int status,String message) { super(message); this.status=status; }
    static void require(boolean condition,int status,String message) { if(!condition) throw new Problem(status,message); }
}
@RestControllerAdvice
class Errors {
    @ExceptionHandler(Problem.class) ResponseEntity<?> problem(Problem e) { return error(e.status,e.getMessage()); }
    @ExceptionHandler(DataIntegrityViolationException.class) ResponseEntity<?> conflict() { return error(409,"This record already exists or is still referenced by research history."); }
    @ExceptionHandler(MaxUploadSizeExceededException.class) ResponseEntity<?> large() { return error(413,"CSV files must be 20 MB or smaller."); }
    @ExceptionHandler({MethodArgumentNotValidException.class,ConstraintViolationException.class,HttpMessageNotReadableException.class,IllegalArgumentException.class}) ResponseEntity<?> invalid(Exception e) { return error(422,"Check the supplied fields and their permitted limits."); }
    private ResponseEntity<?> error(int status,String message) { return ResponseEntity.status(status).body(Map.of("error",Map.of("code","request_"+status,"message",message))); }
}
