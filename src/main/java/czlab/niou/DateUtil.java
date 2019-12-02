/**
 * Copyright © 2013-2019, Kenneth Leung. All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 * which can be found in the file epl-v10.html at the root of this distribution.
 * By using this software in any fashion, you are agreeing to be bound by
 * the terms of this license.
 * You must not remove this notice, or any other, from this software.
 */

package czlab.niou;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 */
public enum DateUtil {
;

  private static ThreadLocal<SimpleDateFormat> _fmt = new ThreadLocal<SimpleDateFormat>() {

    public SimpleDateFormat initialValue() {
      SimpleDateFormat f= new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
      f.setTimeZone(TimeZone.getTimeZone("GMT"));
      return f;
    }

  };

  /**
   */
  public static long parseHttpDate(String s, long defaultValue) {
    try {
      return parseHttpDate(s);
    } catch (ParseException e) {}
    return defaultValue;
  }

  /**
   */
  public static long parseHttpDate(String s) throws ParseException {
    return getSDF().parse(s).getTime();
  }

  /**
   */
  public static String formatHttpDate(long d) {
    GregorianCalendar gc= new GregorianCalendar(TimeZone.getTimeZone("GMT"));
    gc.setTimeInMillis(d);
    return getSDF().format(gc.getTime());
  }

  /**/
  public static String formatHttpDate(Date d) {
    GregorianCalendar gc= new GregorianCalendar(TimeZone.getTimeZone("GMT"));
    gc.setTime(d);
    return getSDF().format(gc.getTime());
  }

  /**/
  public static SimpleDateFormat getSDF() {
    return _fmt.get();
  }

}

