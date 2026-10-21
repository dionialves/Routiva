package br.com.scriptping;
import java.util.List;
public record Host(String id, String name, String ip, List<Group> groups) {
 public record Group(String id, String name) {}
}
