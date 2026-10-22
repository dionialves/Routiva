package br.com.scriptping;
import java.util.List;
public record Host(String id, String name, String ip, List<Group> groups, boolean acknowledgedIcmpProblem) {
 public Host(String id,String name,String ip,List<Group> groups) {this(id,name,ip,groups,false);}
 public record Group(String id, String name) {}
}
