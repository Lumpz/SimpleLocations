# Simple Locations

Lightweight server-side regions that show “Now Entering” text when players walk into an area. Optional “fancy” mode puts big center-screen text with a line and can play a vanilla ambient sound only to that player.

## Requirements

Fabric API

## Features

Create circle or square locations by name and radius

Per-location color for the action bar message

Optional “fancy” mode for Dark Souls style center title with underline

Pure server side. No client mod needed

## Commands

Examples assume you are standing where you want the center.


```
# Create a circle with radius 25
/location create <name> 25

# Create a square with radius 30
/location create <name> 30 square

# Set the display color (name or RGB, depends on your implementation)
/location color <name> red

# Toggle fancy mode for the location you are currently inside
/location fancy true
/location fancy false

# List, remove, rename
/location list
/location remove <name>

