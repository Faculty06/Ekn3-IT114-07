package M3;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Scanner;

/*
Challenge 3: Mad Libs Generator (Randomized Stories)
-----------------------------------------------------
- Load a **random** story from the "stories" folder
- Extract **each line** into a collection (i.e., ArrayList)
- Prompts user for each placeholder (i.e., <adjective>) 
    - Any word the user types is acceptable, no need to verify if it matches the placeholder type
    - Any placeholder with underscores should display with spaces instead
- Replace placeholders with user input (assign back to original slot in collection)
*/

public class MadLibsGenerator extends BaseClass {
    private static final String STORIES_FOLDER = "M3/stories";
    private static String ucid = "ekn3"; // <-- change to your ucid

    public static void main(String[] args) {
        printHeader(ucid, 3,
                "Objective: Implement a Mad Libs generator that replaces placeholders dynamically.");

        Scanner scanner = new Scanner(System.in);
        File folder = new File(STORIES_FOLDER);

        if (!folder.exists() || !folder.isDirectory() || folder.listFiles().length == 0) {
            System.out.println("Error: No stories found in the 'stories' folder.");
            printFooter(ucid, 3);
            scanner.close();
            return;
        }
        // Start edits
        // ekn3 09-13-2025

        // load a random story file
        File[] storyFiles = folder.listFiles();
        File Story = storyFiles[new Random().nextInt(storyFiles.length)];

        List<String> lines = new ArrayList<>();

        try (Scanner fileScanner = new Scanner(Story)) {
            while (fileScanner.hasNextLine()) {
                lines.add(fileScanner.nextLine());
            }
        } catch (FileNotFoundException e) {
            System.out.println("Error reading the story file.");
            scanner.close();
            return;
        }

        
        // parse the story lines
        // iterate through the lines
        // prompt the user for each placeholder (note: there may be more than one
        // placeholder in a line)
        // apply the update to the same collection slot
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            while (line.contains("<")) {
                int start = line.indexOf("<");
                int end = line.indexOf(">", start);  // did a research on line 66 to 69
                if (end == -1) break;

                String placeholder = line.substring(start + 1, end);
                String displayPlaceholder = placeholder.replace("_", " ");
                System.out.print("Enter a " + displayPlaceholder + ": ");
                String userInput = scanner.nextLine();

                line = line.substring(0, start) + userInput + line.substring(end + 1);
            }
            lines.set(i, line);
        }
        System.out.println("\nYour Completed Mad Libs Story:\n");
        for (String line : lines) {
            System.out.println(line);
        }

        // End edits
        System.out.println("\nYour Completed Mad Libs Story:\n");
        StringBuilder finalStory = new StringBuilder();
        for (String line : lines) {
            finalStory.append(line).append("\n");
        }
        System.out.println(finalStory.toString());

        printFooter(ucid, 3);
        scanner.close();
    }

}
