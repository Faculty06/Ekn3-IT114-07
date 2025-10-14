package M3;

/*
Challenge 1: Command-Line Calculator
------------------------------------
- Accept two numbers and an operator as command-line arguments
- Supports addition (+) and subtraction (-)
- Allow integer and floating-point numbers
- Ensures correct decimal places in output based on input (e.g., 0.1 + 0.2 → 1 decimal place)
- Display an error for invalid inputs or unsupported operators
- Capture 5 variations of tests
*/

public class CommandLineCalculator extends BaseClass {
    private static String ucid = "ekn3"; // <-- change to your ucid

    public static void main(String[] args) {
        printHeader(ucid, 1, "Objective: Implement a calculator using command-line arguments.");

        if (args.length != 3) {
            System.out.println("Usage: java M3.CommandLineCalculator <num1> <operator> <num2>");
            printFooter(ucid, 1);
            return;
        }

        try {
            System.out.println("Calculating result...");

            //ekn3 09-13-2025 solution
            
            // extract the equation (format is <num1> <operator> <num2>)
            String num1S = args[0];
            String operator = args[1];
            String num2S = args[2];         

            // check the type of each number and choose appropriate parsing
            double num1 = Double.parseDouble(num1S);
            double num2 = Double.parseDouble(num2S);
            double results;

            // check if operator is addition or subtraction
            if (operator.equals("+")){
                results = num1 + num2;
            }
            else if (operator.equals("-")){            
                results = num1 - num2;
            }
            else{
                System.out.println("Use + or - only.");
                return;
            }
            // generate the equation result (Important: ensure decimals display as the
            // longest decimal passed)
            // i.e., 0.1 + 0.2 would show as one decimal place (0.3), 0.11 + 0.2 would shows
            // as two (0.31), etc

            int decimals1 = num1S.contains(".") ? num1S.length() - num1S.indexOf('.') - 1 : 0;
            int decimals2 = num2S.contains(".") ? num2S.length() - num2S.indexOf('.') - 1 : 0;
            int maxDecimals = Math.max(decimals1, decimals2);

            
            String format = "%." + maxDecimals + "f";
            System.out.println("Results: " + String.format(format, results));




        } 
        catch (Exception e) {
            System.out.println("Invalid input. Please ensure correct format and valid numbers.");
        }

        printFooter(ucid, 1);
    }
}
