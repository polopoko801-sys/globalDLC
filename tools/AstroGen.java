import javax.imageio.ImageIO; import java.awt.*; import java.awt.geom.*; import java.awt.image.BufferedImage; import java.io.File;
/** A small floating astronaut: white suit, rounded helmet, dark visor with a highlight. 64x64, transparent. */
public class AstroGen { public static void main(String[] a) throws Exception {
  int S=64; BufferedImage img=new BufferedImage(S,S,BufferedImage.TYPE_INT_ARGB); Graphics2D g=img.createGraphics();
  g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
  Color suit=new Color(236,238,245), shade=new Color(188,192,205), dark=new Color(60,66,86);
  // backpack
  g.setColor(shade); g.fill(new RoundRectangle2D.Float(20,22,24,22,8,8));
  // limbs
  g.setColor(suit); g.setStroke(new BasicStroke(9,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
  g.draw(new Line2D.Float(24,30,14,44)); g.draw(new Line2D.Float(40,30,52,46)); // arms
  g.draw(new Line2D.Float(28,42,24,58)); g.draw(new Line2D.Float(36,42,42,58)); // legs
  // body
  g.setColor(suit); g.fill(new RoundRectangle2D.Float(22,24,20,22,9,9));
  g.setColor(shade); g.fill(new RoundRectangle2D.Float(29,28,6,12,3,3)); // chest panel
  // helmet
  g.setColor(suit); g.fill(new Ellipse2D.Float(18,8,28,28));
  g.setColor(dark); g.fill(new Ellipse2D.Float(23,13,18,16)); // visor
  g.setColor(new Color(120,170,255,180)); g.fill(new Ellipse2D.Float(26,16,6,5)); // visor glint
  g.dispose(); ImageIO.write(img,"png",new File(a[0])); } }
