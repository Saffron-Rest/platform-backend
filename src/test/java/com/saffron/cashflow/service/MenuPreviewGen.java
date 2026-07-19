package com.saffron.cashflow.service;
import com.saffron.cashflow.domain.MenuCategory;
import com.saffron.cashflow.domain.MenuItem;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
class MenuPreviewGen {
    @Test
    void render() throws IOException {
        Path uploadDir = Files.createTempDirectory("menu-preview");
        Files.createDirectories(uploadDir.resolve("menu"));
        List<MenuCategory> cats = List.of(cat("c2","Drinks",20));
        Map<String,List<MenuItem>> items = new LinkedHashMap<>();
        items.put("c2", List.of(
                drink("c2","Ayran","330ml",8,null),
                sameprice("c2","Coca-Cola", new String[]{"Zero","Classic"}, 8),      // same price, name options
                priced("c2","Lemonade", new String[]{"Small","Large"}, new int[]{9,14}), // per-size prices
                sameprice("c2","Tea", new String[]{"Black","Green","Herbal"}, 6),
                drink("c2","Espresso",null,9,null),
                drink("c2","Still Water","500ml",6,null)));
        MenuService menu = new StubMenuService(cats, items);
        byte[] pdf = new MenuPrintService(menu, new StubFileStorage(uploadDir))
                .buildMenu("photolist","Saffron","Authentic Azerbaijani Restaurant",true,"en");
        Path out = Path.of("/private/tmp/claude-501/-Users-ihakhverdiyev-Desktop-saffron-app/5572d5a5-663d-4159-88a9-291f91aab7b1/scratchpad/menu-preview.pdf");
        Files.write(out, pdf);
        System.out.println("WROTE " + out);
    }
    private static MenuCategory cat(String id,String n,int s){MenuCategory c=new MenuCategory();c.setId(id);c.setName(n);c.setSortOrder(s);c.setActive(true);return c;}
    private static MenuItem drink(String c,String n,String portion,int p,String diet){MenuItem i=new MenuItem();i.setId(n.toLowerCase().replace(' ','-'));i.setCategoryId(c);i.setName(n);i.setSellPrice(new BigDecimal(p));i.setVatRatePct(new BigDecimal("8.00"));i.setActive(true);if(portion!=null)i.setPortionSize(portion);if(diet!=null)i.setDietaryTags(diet);return i;}
    private static MenuItem priced(String c,String n,String[] names,int[] prices){MenuItem i=drink(c,n,null,prices[0],null);StringBuilder sb=new StringBuilder("[");for(int k=0;k<names.length;k++){if(k>0)sb.append(",");sb.append("{\"name\":\"").append(names[k]).append("\",\"price\":").append(prices[k]).append("}");}sb.append("]");i.setVariants(sb.toString());return i;}
    private static MenuItem sameprice(String c,String n,String[] names,int base){MenuItem i=drink(c,n,null,base,null);StringBuilder sb=new StringBuilder("[");for(int k=0;k<names.length;k++){if(k>0)sb.append(",");sb.append("{\"name\":\"").append(names[k]).append("\"}");}sb.append("]");i.setVariants(sb.toString());return i;}
    private static final class StubFileStorage extends FileStorageService {
        private final Path d; StubFileStorage(Path dir) throws IOException{super(dir.toString(),null,null);this.d=dir;}
        @Override public Path getUploadDir(){return d;}
    }
    private static final class StubMenuService extends MenuService {
        private final List<MenuCategory> c; private final Map<String,List<MenuItem>> i;
        StubMenuService(List<MenuCategory> c,Map<String,List<MenuItem>> i){super(null,null,null);this.c=c;this.i=i;}
        @Override public List<MenuCategory> activeCategoriesInOrder(){return c;}
        @Override public List<MenuItem> activeItemsForCategory(String id){return i.getOrDefault(id,List.of());}
    }
}
