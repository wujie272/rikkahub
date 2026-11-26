#!/data/data/com.termux/files/usr/bin/bash
# Generate the rebase todo list
# We'll use sed to modify the default todo list

# List of commits to drop (by their short SHA)
# These are pure overlay/live2d commits
DROP_COMMITS="df8a7e6db|0e9571ca5|475e97ff0|004dfc029|50ceb5ee8|633c7896a|0e02da446|089aa1eb2|7315af268|00ab6d381"

# Replace 'pick' with 'drop' for those commits
sed -i -E "s/^pick ($DROP_COMMITS)/drop \1/" "$1"
