-- FLIXNET Share: shares a folder of movies with FLIXNET on the TV. Opening it starts sharing (in the
-- background, and the app closes); opening it again offers to stop. It never stays open, since an app
-- like this can't be quit while it shows a window.
-- make-mac-app.sh builds it into FLIXNET Share.app, with share.py inside it.

on run
	set pidFile to POSIX path of (path to home folder) & ".flixnet-share.pid"

	-- Already sharing: offer to stop.
	-- (Only if that number is still share.py: after a restart, another program could have it.)
	set sharingPid to ""
	try
		set sharingPid to do shell script "pid=$(cat " & quoted form of pidFile & ") && ps -p $pid -o command= | grep -q share.py && echo $pid"
	end try
	if sharingPid is not "" then
		set sharedFolder to ""
		try
			set sharedFolder to do shell script "defaults read com.knz.flixnet-share folder"
		end try
		set answer to button returned of (display dialog "FLIXNET is sharing:" & return & sharedFolder buttons {"Keep sharing", "Stop sharing"} default button "Stop sharing" with title "FLIXNET Share" with icon note)
		if answer is "Stop sharing" then
			do shell script "kill " & sharingPid & "; rm -f " & quoted form of pidFile
			display notification "Stopped sharing with FLIXNET." with title "FLIXNET Share"
		end if
		return
	end if

	set here to POSIX path of (path to me)
	set sharer to here & "Contents/Resources/share.py"
	set python to findPython()
	if python is "" then
		display dialog "FLIXNET Share needs Python 3, which comes with Apple's free Command Line Tools." & return & return & "Click Install, wait for it to finish, then open FLIXNET Share again." buttons {"Cancel", "Install"} default button "Install" with title "FLIXNET Share" with icon caution
		do shell script "xcode-select --install >/dev/null 2>&1 || true"
		return
	end if

	-- The folder shared last time, offered again.
	set lastFolder to ""
	try
		set lastFolder to do shell script "defaults read com.knz.flixnet-share folder 2>/dev/null"
	end try
	set shareFolder to ""
	if lastFolder is not "" then
		try
			do shell script "test -d " & quoted form of lastFolder
			set answer to button returned of (display dialog "Share this folder with FLIXNET?" & return & return & lastFolder buttons {"Cancel", "Choose another…", "Share"} default button "Share" with title "FLIXNET Share" with icon note)
			if answer is "Share" then set shareFolder to lastFolder
		end try
	end if
	if shareFolder is "" then
		set shareFolder to POSIX path of (choose folder with prompt "Choose the folder with your movies: the drive or folder with Movies and Series in it.")
	end if
	do shell script "defaults write com.knz.flixnet-share folder " & quoted form of shareFolder

	-- In the background, on its own (nohup), so it keeps sharing after this app closes.
	set logFile to "/tmp/flixnet-share.log"
	set pid to do shell script "nohup " & quoted form of python & " " & quoted form of sharer & " " & quoted form of shareFolder & " > " & logFile & " 2>&1 & echo $!"
	delay 2
	-- It stopped straight away: say why (no Movies or Series folder, the port taken…).
	try
		do shell script "kill -0 " & pid
	on error
		set why to do shell script "tail -n 3 " & logFile
		display dialog "FLIXNET Share couldn't start:" & return & return & why buttons {"OK"} default button 1 with title "FLIXNET Share" with icon stop
		return
	end try
	do shell script "echo " & pid & " > " & quoted form of pidFile

	display dialog "Sharing with FLIXNET:" & return & shareFolder & return & return & "Open FLIXNET on the TV: this computer shows up there within 30 seconds." & return & return & "To stop sharing, open FLIXNET Share again." buttons {"OK"} default button 1 with title "FLIXNET Share" with icon note giving up after 20
end run

-- Python 3: Homebrew's, or Apple's once the Command Line Tools are in (without them, /usr/bin/python3
-- is only a stand-in that offers to install them).
on findPython()
	repeat with p in {"/opt/homebrew/bin/python3", "/usr/local/bin/python3"}
		try
			do shell script "test -x " & quoted form of (p as text)
			return p as text
		end try
	end repeat
	try
		do shell script "xcode-select -p >/dev/null 2>&1 && test -x /usr/bin/python3"
		return "/usr/bin/python3"
	end try
	return ""
end findPython
