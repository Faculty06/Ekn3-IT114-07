package M3;

import java.util.Random;

/*
Challenge 2: Simple Slash Command Handler
-----------------------------------------
- Accept user input as slash commands
  - "/greet <name>" → Prints "Hello, <name>!"
  - "/roll <num>d<sides>" → Roll <num> dice with <sides> and returns a single outcome as "Rolled <num>d<sides> and got <result>!"
  - "/echo <message>" → Prints the message back
  - "/quit" → Exits the program
- Commands are case-insensitive
- Print an error for unrecognized commands
- Print errors for invalid command formats (when applicable)
- Capture 3 variations of each command except "/quit"
*/

import java.util.Scanner;

public class SlashCommandHandler extends BaseClass {
    private static String ucid = "ekn3"; // <-- change to your UCID

    public static void main(String[] args) {
        printHeader(ucid, 2, "Objective: Implement a simple slash command parser.");

        Scanner scanner = new Scanner(System.in);

        // Can define any variables needed here

        while (true) {
            System.out.print("Enter command: ");
            // get entered text
            String input = scanner.nextLine().trim();
            String lowerInput = input.toLowerCase();

            // check if greet
            //// process greet
            if (lowerInput.startsWith("/greet ")) {
                String name = input.substring(7).trim();
                if (name.isEmpty()) {
                    System.out.println("Error: /greet requires a name.");
                } else {
                    System.out.println("Hello, " + name + "!");
                }
                continue;
            }


            // check if roll
            //// process roll
             if (lowerInput.startsWith("/roll ")) {
                String dicePart = input.substring(6).trim();
                if (!dicePart.matches("\\d+d\\d+")) {
                    System.out.println("Error: /roll format must be <num>d<sides> (e.g., 2d6).");
                } else {
                    String[] parts = dicePart.split("d");
                    int num = Integer.parseInt(parts[0]);
                    int sides = Integer.parseInt(parts[1]);

                    if (num <= 0 || sides <= 0) {
                        System.out.println("Error: Dice number and sides must be positive integers.");
                    } else {
                        Random rand = new Random();
                        int total = 0;
                        for (int i = 0; i < num; i++) {
                            total += rand.nextInt(sides) + 1;
                        }
                        System.out.println("Rolled " + num + "d" + sides + " and got " + total + "!");
                    }
                }
                continue;
            }

            // check if echo
            //// process echo
            if (lowerInput.startsWith("/echo ")) {
                String message = input.substring(6).trim();
                if (message.isEmpty()) {
                    System.out.println("Error: /echo requires a message.");
                } else {
                    System.out.println(message);
                }
                continue;
            }

            // check if quit
            //// process quit
            if (input.equalsIgnoreCase("/quit")) {
                System.out.println("Goodbye!");
                break;    
        }
        // handle invalid commnads
            System.out.println("Error: Invalid command.");

        printFooter(ucid, 2);
        scanner.close();
        }
    }   
}