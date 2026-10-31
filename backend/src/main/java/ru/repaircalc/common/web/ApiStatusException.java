package ru.repaircalc.common.web;

public class ApiStatusException extends RuntimeException {
  public final int status;
  public final String code;

  public ApiStatusException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}
