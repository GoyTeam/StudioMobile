package dev.falix.studiomobile;

import java.util.*;

final class StudioPrefabs {
    static final class Prefab {
        final String id, title, category, description;
        Prefab(String id, String title, String category, String description) {
            this.id = id;
            this.title = title;
            this.category = category;
            this.description = description;
        }
    }

    static final Prefab[] ALL = {
        new Prefab("kill_brick", "Kill Brick", "Obby", "Neon red lava brick that respawns the player on touch"),
        new Prefab("checkpoint", "Checkpoint Pad", "Obby", "Saves player respawn point and flashes green on touch"),
        new Prefab("speed_pad", "Speed Boost Pad", "Obby", "Boosts WalkSpeed to 38 for 4 seconds on touch"),
        new Prefab("jump_pad", "Super Jump Pad", "Obby", "Launches the player high into the air on touch"),
        new Prefab("spinner", "Spinning Beam", "Mechanics", "Anchored neon hazard beam rotating smoothly via Luau"),
        new Prefab("elevator", "Moving Platform", "Mechanics", "Smooth up-and-down platform driven by Heartbeat"),
        new Prefab("rainbow", "Rainbow Neon Block", "Mechanics", "Cycles HSV neon colors continuously via Luau"),
        new Prefab("hud_button", "Interactive HUD UI", "UI", "StarterGui ScreenGui with clickable counter TextButton"),
        new Prefab("lamp", "Street Lamp", "Models", "Metallic pole, arm, neon bulb, and warm PointLight"),
        new Prefab("tree", "Stylized Tree", "Models", "Wood trunk with layered grass canopy"),
        new Prefab("house", "Starter Cabin", "Models", "Walls, doorway, pitched roof, and interior PointLight"),
        new Prefab("obby_starter", "Obby Course Pack", "Obby", "5 stepping platforms, spinning beam, and finish checkpoint")
    };

    static Instance spawn(EngineScene scene, String id) {
        if (scene == null || scene.root == null) return null;
        double x = scene.focusPoint[0];
        double y = Math.max(0.5, scene.focusPoint[1]);
        double z = scene.focusPoint[2];
        if (scene.selection != null && scene.selection.isRenderable()) {
            double[] c = scene.selection.cframe();
            x = c[0];
            y = c[1] + scene.selection.size()[1] / 2.0 + 1.0;
            z = c[2];
        }
        scene.pushUndo();
        Instance created = null;
        switch (id) {
            case "kill_brick":
                created = makePart("KillBrick", x, y, z, 6, 1, 6, 0xE53935, 288, true);
                addScript(created, "KillScript",
                        "local part = script.Parent\n"
                        + "part.Touched:Connect(function(hit)\n"
                        + "    local hum = hit.Parent and hit.Parent:FindFirstChild(\"Humanoid\")\n"
                        + "    if hum then\n"
                        + "        hum.Health = 0\n"
                        + "        print(\"Player touched KillBrick!\")\n"
                        + "    end\n"
                        + "end)\n");
                scene.workspace.add(created);
                break;
            case "checkpoint":
                created = makePart("Checkpoint", x, y, z, 6, 0.6, 6, 0x00BCD4, 288, true);
                addScript(created, "CheckpointScript",
                        "local pad = script.Parent\n"
                        + "local claimed = false\n"
                        + "pad.Touched:Connect(function(hit)\n"
                        + "    if claimed then return end\n"
                        + "    local hum = hit.Parent and hit.Parent:FindFirstChild(\"Humanoid\")\n"
                        + "    if hum then\n"
                        + "        claimed = true\n"
                        + "        pad.Color = Color3.fromRGB(46, 204, 113)\n"
                        + "        print(\"Checkpoint reached: \" .. pad.Name)\n"
                        + "    end\n"
                        + "end)\n");
                scene.workspace.add(created);
                break;
            case "speed_pad":
                created = makePart("SpeedPad", x, y, z, 6, 0.5, 6, 0x2ECC71, 288, true);
                addScript(created, "SpeedScript",
                        "local pad = script.Parent\n"
                        + "local debounce = false\n"
                        + "pad.Touched:Connect(function(hit)\n"
                        + "    if debounce then return end\n"
                        + "    local hum = hit.Parent and hit.Parent:FindFirstChild(\"Humanoid\")\n"
                        + "    if hum then\n"
                        + "        debounce = true\n"
                        + "        hum.WalkSpeed = 38\n"
                        + "        print(\"Speed boost active!\")\n"
                        + "        task.wait(4)\n"
                        + "        hum.WalkSpeed = 16\n"
                        + "        debounce = false\n"
                        + "    end\n"
                        + "end)\n");
                scene.workspace.add(created);
                break;
            case "jump_pad":
                created = makePart("JumpPad", x, y, z, 6, 0.5, 6, 0xFFD600, 288, true);
                addScript(created, "JumpScript",
                        "local pad = script.Parent\n"
                        + "local debounce = false\n"
                        + "pad.Touched:Connect(function(hit)\n"
                        + "    if debounce then return end\n"
                        + "    local hum = hit.Parent and hit.Parent:FindFirstChild(\"Humanoid\")\n"
                        + "    if hum then\n"
                        + "        debounce = true\n"
                        + "        hum.JumpPower = 110\n"
                        + "        hum.Jump = true\n"
                        + "        print(\"Super jump!\")\n"
                        + "        task.wait(1.2)\n"
                        + "        hum.JumpPower = 50\n"
                        + "        debounce = false\n"
                        + "    end\n"
                        + "end)\n");
                scene.workspace.add(created);
                break;
            case "spinner":
                created = makePart("SpinningBeam", x, y + 1.5, z, 14, 1.2, 1.5, 0xFF5252, 288, true);
                addScript(created, "SpinScript",
                        "local RunService = game:GetService(\"RunService\")\n"
                        + "local beam = script.Parent\n"
                        + "RunService.Heartbeat:Connect(function(dt)\n"
                        + "    beam.CFrame = beam.CFrame * CFrame.Angles(0, dt * 1.6, 0)\n"
                        + "end)\n");
                scene.workspace.add(created);
                break;
            case "elevator":
                created = makePart("MovingPlatform", x, y, z, 8, 1, 8, 0x42A5F5, 1088, true);
                addScript(created, "MoveScript",
                        "local RunService = game:GetService(\"RunService\")\n"
                        + "local plat = script.Parent\n"
                        + "local startPos = plat.Position\n"
                        + "local t = 0\n"
                        + "RunService.Heartbeat:Connect(function(dt)\n"
                        + "    t += dt\n"
                        + "    local offset = math.sin(t * 1.4) * 6\n"
                        + "    plat.Position = Vector3.new(startPos.X, startPos.Y + offset, startPos.Z)\n"
                        + "end)\n");
                scene.workspace.add(created);
                break;
            case "rainbow":
                created = makePart("RainbowBlock", x, y + 1.5, z, 4, 4, 4, 0xFF4081, 288, true);
                addScript(created, "RainbowScript",
                        "local RunService = game:GetService(\"RunService\")\n"
                        + "local part = script.Parent\n"
                        + "local hue = 0\n"
                        + "RunService.Heartbeat:Connect(function(dt)\n"
                        + "    hue = (hue + dt * 0.25) % 1\n"
                        + "    part.Color = Color3.fromHSV(hue, 0.85, 1)\n"
                        + "end)\n");
                scene.workspace.add(created);
                break;
            case "hud_button": {
                Instance starterGui = scene.root.findFirst("StarterGui");
                if (starterGui == null) {
                    starterGui = new Instance("StarterGui", "StarterGui");
                    scene.root.add(starterGui);
                }
                Instance gui = new Instance("ScreenGui", "CounterHUD");
                gui.props.put("Enabled", true);
                Instance btn = new Instance("TextButton", "ClickButton");
                btn.props.put("Text", "Clicks: 0");
                btn.props.put("BackgroundColor3", new Instance.Vector3(0.20, 0.37, 1.0));
                btn.props.put("TextColor3", new Instance.Vector3(1.0, 1.0, 1.0));
                btn.props.put("Visible", true);
                addLocalScript(btn, "ButtonLogic",
                        "local button = script.Parent\n"
                        + "local clicks = 0\n"
                        + "button.MouseButton1Click:Connect(function()\n"
                        + "    clicks += 1\n"
                        + "    button.Text = \"Clicks: \" .. tostring(clicks)\n"
                        + "    print(\"HUD button clicked: \" .. clicks)\n"
                        + "end)\n");
                gui.add(btn);
                starterGui.add(gui);
                created = gui;
                break;
            }
            case "lamp": {
                Instance model = new Instance("Model", "StreetLamp");
                model.add(makePart("Pole", x, y + 5, z, 0.8, 10, 0.8, 0x37474F, 1088, true));
                model.add(makePart("Arm", x + 1.4, y + 9.8, z, 3.2, 0.5, 0.5, 0x37474F, 1088, true));
                Instance bulb = makePart("Bulb", x + 2.6, y + 9.2, z, 1.1, 0.8, 1.1, 0xFFF59D, 288, true);
                Instance light = new Instance("PointLight", "LampLight");
                light.props.put("Brightness", 2.5d);
                light.props.put("Range", 28d);
                light.props.put("Color", new Instance.Vector3(1.0, 0.94, 0.72));
                light.props.put("Enabled", true);
                bulb.add(light);
                model.add(bulb);
                scene.workspace.add(model);
                created = model;
                break;
            }
            case "tree": {
                Instance model = new Instance("Model", "Tree");
                Instance trunk = makePart("Trunk", x, y + 3.5, z, 1.8, 7, 1.8, 0x6D4C41, 512, true);
                trunk.props.put("Shape", 2);
                Instance leaf1 = makePart("CanopyBottom", x, y + 8, z, 7, 3.5, 7, 0x388E3C, 1280, true);
                leaf1.props.put("Shape", 0);
                Instance leaf2 = makePart("CanopyTop", x, y + 10.5, z, 5, 3, 5, 0x43A047, 1280, true);
                leaf2.props.put("Shape", 0);
                model.add(trunk);
                model.add(leaf1);
                model.add(leaf2);
                scene.workspace.add(model);
                created = model;
                break;
            }
            case "house": {
                Instance model = new Instance("Model", "StarterCabin");
                model.add(makePart("Floor", x, y, z, 16, 1, 14, 0x8D6E63, 528, true));
                model.add(makePart("BackWall", x, y + 5, z - 6.5, 16, 9, 1, 0xD7CCC8, 848, true));
                model.add(makePart("LeftWall", x - 7.5, y + 5, z, 1, 9, 12, 0xD7CCC8, 848, true));
                model.add(makePart("RightWall", x + 7.5, y + 5, z, 1, 9, 12, 0xD7CCC8, 848, true));
                model.add(makePart("FrontLeft", x - 5, y + 5, z + 6.5, 6, 9, 1, 0xD7CCC8, 848, true));
                model.add(makePart("FrontRight", x + 5, y + 5, z + 6.5, 6, 9, 1, 0xD7CCC8, 848, true));
                model.add(makePart("Roof", x, y + 10.2, z, 18, 1.4, 16, 0x5D4037, 512, true));
                Instance ceiling = makePart("CeilingLight", x, y + 9.2, z, 2, 0.4, 2, 0xFFF9C4, 288, true);
                Instance pl = new Instance("PointLight", "RoomLight");
                pl.props.put("Brightness", 2.0d);
                pl.props.put("Range", 24d);
                pl.props.put("Color", new Instance.Vector3(1.0, 0.96, 0.85));
                pl.props.put("Enabled", true);
                ceiling.add(pl);
                model.add(ceiling);
                scene.workspace.add(model);
                created = model;
                break;
            }
            case "obby_starter": {
                Instance model = new Instance("Model", "ObbyCourse");
                int[] colors = {0x42A5F5, 0x66BB6A, 0xFFA726, 0xAB47BC, 0xEC407A};
                for (int i = 0; i < 5; i++) {
                    model.add(makePart("Stage" + (i + 1), x, y + i * 1.8, z - (i + 1) * 9, 6, 1, 6, colors[i], 256, true));
                }
                Instance beam = makePart("HazardBeam", x, y + 6.5, z - 27, 12, 1, 1.2, 0xE53935, 288, true);
                addScript(beam, "SpinHazard",
                        "local RunService = game:GetService(\"RunService\")\n"
                        + "local beam = script.Parent\n"
                        + "RunService.Heartbeat:Connect(function(dt)\n"
                        + "    beam.CFrame = beam.CFrame * CFrame.Angles(0, dt * 1.5, 0)\n"
                        + "end)\n");
                model.add(beam);
                Instance finish = makePart("FinishPad", x, y + 9.5, z - 54, 8, 1, 8, 0xFFD600, 288, true);
                addScript(finish, "WinScript",
                        "local pad = script.Parent\n"
                        + "local won = false\n"
                        + "pad.Touched:Connect(function(hit)\n"
                        + "    if won then return end\n"
                        + "    if hit.Parent and hit.Parent:FindFirstChild(\"Humanoid\") then\n"
                        + "        won = true\n"
                        + "        pad.Color = Color3.fromRGB(0, 230, 118)\n"
                        + "        print(\"You beat the Obby!\")\n"
                        + "    end\n"
                        + "end)\n");
                model.add(finish);
                scene.workspace.add(model);
                created = model;
                break;
            }
            default:
                return null;
        }
        scene.selection = created;
        scene.markDirty();
        return created;
    }

    private static Instance makePart(String name, double x, double y, double z, double sx, double sy, double sz, int color, int material, boolean anchored) {
        Instance p = Instance.create("Part", name);
        p.props.put("size", new Instance.Vector3(sx, sy, sz));
        p.props.put("CFrame", new double[]{x, y, z, 1, 0, 0, 0, 1, 0, 0, 0, 1});
        p.props.put("Color3uint8", color);
        p.props.put("Material", material);
        p.props.put("Anchored", anchored);
        p.props.put("CanCollide", true);
        return p;
    }

    private static void addScript(Instance parent, String name, String source) {
        Instance s = new Instance("Script", name);
        s.props.put("Source", source);
        s.tags.put("Source", "ProtectedString");
        parent.add(s);
    }

    private static void addLocalScript(Instance parent, String name, String source) {
        Instance s = new Instance("LocalScript", name);
        s.props.put("Source", source);
        s.tags.put("Source", "ProtectedString");
        parent.add(s);
    }
}
