package br.com.scriptping;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
@Component
public class PingProbe {
 private static final Pattern TIME=Pattern.compile("(?:time|tempo)([=<])\\s*([0-9]+(?:[.,][0-9]+)?)\\s*ms",Pattern.CASE_INSENSITIVE);
 public record Result(Double latency,String error) {}
 public Result ping(String ip,int timeoutMs) {
  if(ip==null || !ip.matches("[0-9a-fA-F:.]+")) return new Result(null,"IP ausente ou inválido");
  Process process=null;
  try {
   boolean windows=System.getProperty("os.name").toLowerCase().contains("win");
   ProcessBuilder builder=windows?new ProcessBuilder("ping","-n","1","-w",String.valueOf(timeoutMs),ip):new ProcessBuilder("ping","-n","-c","1",ip);
   builder.environment().put("LC_ALL","C"); process=builder.redirectErrorStream(true).start();
   if(!process.waitFor(timeoutMs,TimeUnit.MILLISECONDS)) return new Result(null,"Tempo esgotado");
   String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
   var match=TIME.matcher(output);
   if(process.exitValue()==0 && match.find()) return new Result(Double.parseDouble(match.group(2).replace(',','.')),null);
   return new Result(null,"Sem resposta ICMP");
  } catch(InterruptedException ex) {Thread.currentThread().interrupt(); return new Result(null,"Interrompido");}
  catch(Exception ex) {return new Result(null,"Falha ao executar ping");}
  finally {if(process!=null && process.isAlive()) process.destroyForcibly();}
 }
}
