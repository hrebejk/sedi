/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package cz.hrebejk.sedi.demo;

import cz.hrebejk.sedi.demo.lib.Greertr;
import static cz.hrebejk.sedi.demo.lib.Gretaings.*;
import cz.hrebejk.sedi.demo.lib.YeahNay;
import static cz.hrebejk.sedi.demo.lib.YeahNay.NAY;
import static cz.hrebejk.sedi.demo.lib.YeahNay.YEAH;

/**
 *
 * @author phrebejk
 */
public class Use implements Greertr {
    
    private final String EMPTY = empty();
    
    private String[] greetings = new String[] {
       DOBRY_DEN,
       CAU,
       NAZDAR
    };

    
    private String great(YeahNay polite, boolean very) {
        
        if ( polite == YEAH ) {
            return DOBRY_DEN;
        }
        else {
            return !very ? CAU : NAZDAR;
        }
        
    }

    @Override
    public String great()  {
        return great( NAY, false );
    }


    public static void main(String... args) {
        Use m = new Use();
        if ( args.length == 0 ) {
            m.great();
        }
    }


}
