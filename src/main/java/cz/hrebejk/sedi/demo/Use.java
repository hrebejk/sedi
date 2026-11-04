/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package cz.hrebejk.sedi.demo;

import cz.hrebejk.sedi.demo.lib.Greertr;
import cz.hrebejk.sedi.demo.lib.Gretaings;
import cz.hrebejk.sedi.demo.lib.YeahNay;

/**
 *
 * @author phrebejk
 */
public class Use implements Greertr {
    
    private final String EMPTY = cz.hrebejk.sedi.demo.lib.Gretaings.empty();
    
    private String[] greetings = new String[] {
       Gretaings.DOBRY_DEN,
       cz.hrebejk.sedi.demo.lib.Gretaings.CAU,
       cz.hrebejk.sedi.demo.lib.Gretaings.NAZDAR
    };
         
    private String great(YeahNay polite, boolean b) {
        
        if ( polite == cz.hrebejk.sedi.demo.lib.YeahNay.YEAH ) {
            return Gretaings.DOBRY_DEN;
        }
        else {
            return b ? Gretaings.CAU : Gretaings.NAZDAR;
        }
        
    }

    @Override
    public String great()  {
        return great( YeahNay.NAY, false );
    }

    public static void main(String... ps) {
        Use m = new Use();
        if ( ps.length == 0 ) {
            m.great();
        }
    }


}
