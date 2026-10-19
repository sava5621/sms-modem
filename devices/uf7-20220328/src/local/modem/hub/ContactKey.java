package local.modem.hub;
import java.util.Locale;
final class ContactKey {
 static String of(String value){String s=value==null?"":value.trim();if(s.matches("[+0-9 ()-]+")){s=s.replaceAll("[^0-9]","");if(s.length()==11&&s.startsWith("8"))s="7"+s.substring(1);return s;}return s.toLowerCase(Locale.ROOT);}
 static String search(String value){return value==null?"":value.toLowerCase(Locale.ROOT);}
 static String pattern(String value){return "*"+search(value).replace("[","[[]").replace("*","[*]").replace("?","[?]")+"*";}
}
