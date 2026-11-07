/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package cz.hrebejk.sedi.demo;

import cz.hrebejk.sedi.demo.lib.Greeter;
import static cz.hrebejk.sedi.demo.lib.Greetings.*;
import cz.hrebejk.sedi.demo.lib.YesNo;
import static cz.hrebejk.sedi.demo.lib.YesNo.NAY;
import static cz.hrebejk.sedi.demo.lib.YesNo.YEAH;

/**
 *
 * @author phrebejk
 */
public class Use implements Greeter {
    
    private final String EMPTY = empty();
    
    private String[] greetings = new String[] {
       DOBRY_DEN,
       CAU,
       NAZDAR
    };

    
    private String great(YesNo polite, boolean very) {
        
        if ( polite == YEAH ) {
            return DOBRY_DEN;
        }
        else {
            return !very ? CAU : NAZDAR;
        }
        
    }

    @Override
    public String greet()  {
        return great( NAY, false );
    }


    public static void main(String... args) {
        Use m = new Use();
        if ( args.length == 0 ) {
            m.greet();
        }
    }


}
