#ifndef MOONCLICKER_UI_MODULE_H
#define MOONCLICKER_UI_MODULE_H

/**
 * `ui` 模組的 Lua 原始碼（#199）。取樹在 native（ScriptHost.viewTree），選擇器與等待在 Lua：
 * 樹已經是 Lua table，在這裡比對比在 C++ 走 table 簡單。
 *
 * registerApi 以 (tree_json, now_ms, decode, sleep) 呼叫這段程式並把回傳值設成全域 `ui`。
 * 用到的標準函式在載入時就存成 local：之後腳本改掉全域也不影響。
 */
constexpr const char *kUiModuleSource = R"lua(
local tree_json, now_ms, decode, sleep = ...
local error, ipairs, pairs, pcall, type, tostring = error, ipairs, pairs, pcall, type, tostring
local tointeger, min = math.tointeger, math.min
local find, sub, gsub = string.find, string.sub, string.gsub

local DEFAULT_TIMEOUT_MS = 10000
local DEFAULT_STEP_MS = 200

local STRING_FIELDS = {
    text = true, text_contains = true, desc = true, desc_contains = true,
    id = true, class = true, package = true,
}
local BOOLEAN_FIELDS = {
    clickable = true, long_clickable = true, checkable = true, checked = true, enabled = true,
    focusable = true, focused = true, selected = true, scrollable = true, editable = true,
    visible = true,
}
local INTEGER_FIELDS = { "x", "y", "w", "h", "cx", "cy" }

-- cjson 把數字都解成浮點數；座標轉回整數，印出來才不是 100.0。
local function normalize(node, package)
    for _, key in ipairs(INTEGER_FIELDS) do
        node[key] = tointeger(node[key]) or node[key]
    end
    node.package = package
    for _, child in ipairs(node.children) do normalize(child, package) end
end

-- 失敗時回傳 nil 與不帶位置的訊息，由公開函式以 error(message, 2) 指到腳本的呼叫處。
local function dump(fn)
    local ok, json = pcall(tree_json, fn)
    if not ok then return nil, (gsub(tostring(json), "^ui:%d+: ", "")) end
    local windows = decode(json)
    for _, window in ipairs(windows) do
        if window.root then normalize(window.root, window.package) end
    end
    return windows
end

local function ends_with(value, suffix)
    return #value >= #suffix and sub(value, -#suffix) == suffix
end

local function compile(selector, fn)
    if type(selector) == "string" then selector = { text = selector } end
    if type(selector) ~= "table" then
        return nil, fn .. ": selector must be a string or a table"
    end
    local compiled = { visible = true }
    for key, value in pairs(selector) do
        local expected = STRING_FIELDS[key] and "string" or BOOLEAN_FIELDS[key] and "boolean"
        if not expected then
            return nil, fn .. ": unknown selector field '" .. tostring(key) .. "'"
        end
        if type(value) ~= expected then
            return nil, fn .. ": selector field '" .. key .. "' must be a " .. expected
        end
        compiled[key] = value
    end
    return compiled
end

local function contains(value, part)
    return value ~= nil and find(value, part, 1, true) ~= nil
end

local function matches(node, selector)
    for key, want in pairs(selector) do
        local value = node[key == "text_contains" and "text" or key == "desc_contains" and "desc" or key]
        local ok
        if key == "text_contains" or key == "desc_contains" then
            ok = contains(value, want)
        elseif key == "id" then
            ok = value ~= nil and (value == want or ends_with(value, ":id/" .. want))
        elseif key == "class" then
            ok = value ~= nil and (value == want or ends_with(value, "." .. want))
        else
            ok = value == want
        end
        if not ok then return false end
    end
    return true
end

-- 依視窗順序（最上層在前）、每個視窗內先序走訪。all 為 false 時回傳第一個命中。
local function search(windows, selector, all)
    local found = {}
    local function walk(node)
        if matches(node, selector) then
            if not all then return node end
            found[#found + 1] = node
        end
        for _, child in ipairs(node.children) do
            local hit = walk(child)
            if hit then return hit end
        end
    end
    for _, window in ipairs(windows) do
        if window.root then
            local hit = walk(window.root)
            if hit then return hit end
        end
    end
    if all then return found end
    return nil
end

local function integer_arg(value, default, name, fn)
    if value == nil then return default end
    local n = tointeger(value)
    if n == nil or n < 0 then
        error(fn .. ": " .. name .. " must be a non-negative integer", 3)
    end
    return n
end

local ui = {}

function ui.dump()
    local windows, err = dump("ui.dump")
    if not windows then error(err, 2) end
    return windows
end

local function find_with(fn, selector, all)
    local compiled, err = compile(selector, fn)
    if not compiled then error(err, 3) end
    local windows
    windows, err = dump(fn)
    if not windows then error(err, 3) end
    return search(windows, compiled, all)
end

function ui.find(selector)
    return find_with("ui.find", selector, false)
end

function ui.find_all(selector)
    return find_with("ui.find_all", selector, true)
end

function ui.wait(selector, timeout_ms, step_ms)
    local compiled, err = compile(selector, "ui.wait")
    if not compiled then error(err, 2) end
    timeout_ms = integer_arg(timeout_ms, DEFAULT_TIMEOUT_MS, "timeout_ms", "ui.wait")
    step_ms = integer_arg(step_ms, DEFAULT_STEP_MS, "step_ms", "ui.wait")
    local deadline = now_ms() + timeout_ms
    while true do
        local started = now_ms()
        local windows
        windows, err = dump("ui.wait")
        if not windows then error(err, 2) end
        local hit = search(windows, compiled, false)
        if hit then return hit end
        local remaining = deadline - now_ms()
        if remaining <= 0 then return nil end
        local pause = min(started + step_ms - now_ms(), remaining)
        if pause > 0 then sleep(pause) end
    end
end

return ui
)lua";

#endif //MOONCLICKER_UI_MODULE_H
