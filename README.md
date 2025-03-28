# Messages To The Castle
From Boox -> Dropbox -> Raspberry Pi -> Mega

## Description
_This README acts as a journal of all the progress I've made on this project and how I continue to develop it._


_Attempting to transfer my notes from my Boox notebook to my MEGA cloud storage. The working iteration of the project
involves orchestrating CLI commands to download note files from Dropbox to a Raspberry Pi, then upload those files
to MEGA. Along the transfer process, we're also adding more PDF features like watermarks with my logo, PDF passwords
(**more to show that I can add a password rather than it being effective**), adding a PDF page outline and making
the lines of the PDF highlightable (**by adding a bunch of invisible characters to the PDF XD**)._

## Table of Contents
- [Past Problems in Mk. 1](#past-problems-in-mk-1)
    - [Evernote](#evernote)
    - [Dropbox](#dropbox)
        - [With Automation](#with-automation)
        - [What was the Fix?](#what-was-the-fix)
    - [OneNote](#onenote)
- [Documentation to Reference](#documentation-to-reference)
    - [Evernote](#evernote-1)
    - [Dropbox](#dropbox-1)
- [Mk. 2 (Fully Autonomous)](#mk-2-fully-autonomous)
    - [Take 1 - HTML Unit](#html-unit---take-1)
        - [Problem](#problem)
    - [Take 2 - HBrowser](#hbrowser---take-2)
        - [Problem](#problem-1)
    - [Take 3 - Unofficial Dropbox CLI](#unofficial-dropbox-cli---take-3)
        - [Objective](#objective)
        - [What does this solve?](#what-does-this-solve)
        - [Minor Problem](#minor-problem)
- [Operations](#operations)
- [Future Operations](#future-operations)


## Past Problems in Mk. 1
### Evernote
- Getting a BAD_REQUEST error when creating the `NoteStoreClient`
    - Decided to stop trying to find the root cause and focus on a Dropbox solution

### Dropbox
#### With Automation
- Access tokens have a lifetime of **4 hours**
- Currently, don't know how to get a refresh token
    - I think I'll have to manually hit some endpoint, but I don't know what endpoint I have to look for, documentation
      was unclear
##### What was the Fix?
- Manually upload the files whenever I feel like it XD
    - Pretty cringe

### OneNote
- Is there even a Java API??
    - Not that I could find as of writing this



## Documentation to Reference
### Evernote
- [GitHub](https://github.com/evernote/evernote-sdk-java)
    - [Java Code example](https://github.com/evernote/evernote-sdk-java/blob/master/sample/client/EDAMDemo.java)
- [Evernote Javadocs](https://dev.evernote.com/doc/reference/javadoc/)


### Dropbox
- [Developer Guide](https://www.dropbox.com/developers/reference/developer-guide)
- [OAuth Guide](https://developers.dropbox.com/oauth-guide)


## Mk. 2 (Fully Autonomous)
Going through several ideas and iterations of the project to make it function without '**human intervention**'

### HTML Unit - Take 1
Attempt 1 used this library to render the webpage

Citation: [GitHub Link](https://github.com/HtmlUnit/htmlunit)

#### Problem
- The site would throw an exception when Javascript was enabled; however, Javascript is necessary for the page to
  render correctly for future use

### HBrowser - Take 2
Attempt 2 used the HBrowser library to try to do the same thing as HTML Unit

Citation: [GitHub Link](https://github.com/Osiris-Team/HBrowser)

#### Problem
- The site wouldn't render completely
    - Not sure how much time I'd want to spend figuring out if it's plausible to get a completely rendered page


### Unofficial Dropbox CLI - Take 3
For attempt 3, there is an unofficial Dropbox Command Line Interface written in Go and has compiled versions for
Windows and Debian Linux via `linux-arm`. **Perfect!!**

Now we can move forward with an idea.

Citation: [GitHub Link](https://github.com/dropbox/dbxcli)

#### Objective
Design a Java wrapper around the CLI similar to [Eliux MEGA cmd4J library](https://github.com/EliuX/MEGAcmd4J)

#### What does this solve?
- This fixes the access lifetime issue
    - You log into the CLI once and **that's it**
- The CLI compatibility with Windows and Linux made for easy testing before deploying to the Raspberry Pi

#### Minor Problem
- **Premise:** The program is dysfunctional when running the JAR file on a Raspberry Pi Cron Job
- **Way to look into the problem:** Changed the internal logging library to `ch.qos.logback:logback-classic`
  ([GitHub Link](https://github.com/qos-ch/logback))
    - This contains a dependency to `SLF4J-api` so that swapping libraries is no problem
    - With the library change, I can output the log events to a file rather than relying on the console for everything
        - Since console logging doesn't work for Cron Jobs anyway
- **Logging event:** The error that was occurring was `Cannot run program "dbxcli": error=2, No such file or directory`
    - This tells me that the `dbxcli` command is not getting recognized by the system.
        - So, my thought process was '_Is the user different for the Cron Scheduler?_'
- **Solution:** In Linux, we can prepend a command with `sudo -u [USERNAME]` so that the command can be <u>executed by
  a particular user with their set of permissions</u>. In other words, I can substitute myself in as the user running
  the `jar` file

### Operations
1. If the `Crash-Cloud-Path` argument is set `True`, then we wipe the cloud path where we store notes in MEGA before
   the transfer
    - Also deletes the `revision-list.txt` if it exists so that we can conduct a full transfer from Dropbox to MEGA
2. Fetch the full list of files from Dropbox
    - The Dropbox CLI sometimes would cut the `ls` command prematurely, so we weren't able to get the list of files
        - The fix for this is to try again with a linear drop-off rate similar to how browsers employ
          [Exponential Backoff](https://en.wikipedia.org/wiki/Exponential_backoff) for inaccessible websites to
          prevent heavy traffic
3. Sort files strictly by filename (_Excluding the absolute path_)
4. Detect any **file movements** within the Dropbox system
    - A file movement is defined as the same file that appears twice in Dropbox with a difference of one directory
        - This could indicate that the file was moved to a new directory and the older file should be deleted
        - It can also indicate that a folder was renamed somewhere along the absolute path leading to the file
5. If any file movements are found...
    - Determine the older file among the pair (_Older files are determined by older ages and smaller file sizes_)
        - Delete the older file from Dropbox
        - Remove the older file from the fetched file list
        - If an older file cannot be determined, disregard the pair
6. If the `revision-list.txt` file exists, filter out all Dropbox files that haven't changed since last transfer
    - If it filters out all files, the program stops here. (_No new files were added to Dropbox_)
7. Rewrite the `revision-list.txt` file with all new files and hash codes
8. Prepare files for transfer
    - If the amount of files reaches a certain threshold, conduct the transfers in parallel
    - Download the PDFs from Dropbox (_one at a time because some notes will have the same name, and we try
      to prevent local overwriting as much as possible_)
    - Process the files
        - Make the lines of each page highlightable
        - Add a simple table of contents that labels each page with the `Page No.`
        - Encrypting PDFs and locking permissions for certain operations (*more for fun than security. PDF security is
          garbage, [see here](documentation/Problems%20with%20PDF%20Password%20Protection.md)*)
        - Adding a watermark to each page of the '_Jragon_' logo
9. Log and remove any erroneous files from the `revision-list.txt` file so that the program can try again at a
   later time


### Future Operations
- [ ] Make a simulation mode for other people to run
- [ ] Creating a file that extracts all the important info from the notes so that we can plug them into the Handwriting Note Indexer Project
- [x] Making the highlightable lines more accommodating to notes with differing line counts
- [x] Add a progress bar for the CLI when running this program manually
    - [x] Let's make it more exciting with sub-progress bars for the sequential and parallel manual runs
- [x] Wiping empty Dropbox directories after movements are conducted
