export const categories = [
  { id: 1, name: "显示器", icon: "monitor" },
  { id: 2, name: "打印机", icon: "printer" },
  { id: 3, name: "鼠标", icon: "mouse" },
  { id: 4, name: "键盘", icon: "keyboard" },
  { id: 5, name: "摄像头", icon: "camera" },
  { id: 6, name: "耳机", icon: "headphone" },
];
export const categoryOf = (id) =>
  categories.find((x) => x.id === Number(id)) || {
    name: "办公设备",
    icon: "box",
  };
export const money = (cents) =>
  cents == null
    ? "—"
    : (Number(cents) / 100).toLocaleString("zh-CN", {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2,
      });
export function toCents(value, label = "售价") {
  if (!/^[0-9]{1,7}([.][0-9]{1,2})?$/.test(String(value)))
    throw new Error(`请输入有效${label}，最多两位小数`);
  const [whole, fraction = ""] = String(value).split(".");
  const cents = Number(whole) * 100 + Number(fraction.padEnd(2, "0"));
  if (cents < 1 || cents > 100000000)
    throw new Error(`${label}应为0.01至1,000,000元`);
  return cents;
}
export const statusName = (value) =>
  ({
    DRAFT: "草稿",
    PUBLISHED: "已发布",
    HIDDEN: "已隐藏",
    ACTIVE: "当前有效",
    SUPERSEDED: "历史版本",
    RETIRED: "已退役",
  })[value] || value;
export function safeUrl(value) {
  try {
    const url = new URL(value);
    return ["http:", "https:"].includes(url.protocol) &&
      !url.username &&
      !url.password
      ? url.href
      : null;
  } catch {
    return null;
  }
}
export function productImageUrl(value) {
  if (typeof value === "string" && /^\/api\/catalog\/products\/[1-9]\d*\/image\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(png|jpg)$/.test(value)) return value;
  return safeUrl(value);
}
export const specLabels = {
  source_region: "资料地区",
  screen_inches: "屏幕尺寸（英寸）",
  resolution: "分辨率",
  refresh_rate_hz: "刷新率（Hz）",
  usb_c_role: "USB-C用途",
  printing_technology: "打印技术",
  print_color: "打印颜色",
  automatic_duplex: "自动双面打印",
  power_input: "输入电源",
  functions: "功能",
  consumable: "耗材类型",
  privacy_shutter: "隐私遮挡盖",
  focus: "对焦方式",
  earcup_controls: "耳罩控制",
  receiver_protocol: "接收器协议",
  scrolling: "滚轮模式",
  hand_orientation: "手型",
  connections: "连接方式",
  max_switchable_devices: "可切换设备数",
  shortcut_key_count: "快捷键数量",
  keyboard_layout_region: "键位地区",

  hardware_model: "硬件型号",
  connection: "连接方式",
  sensor: "传感器",
  dpi_nominal: "标称 DPI",
  battery: "电池",
  cable_length_m: "线长（米）",
  button_count: "按键数量",
  layout_size: "键盘尺寸",
  number_pad: "数字键区",
  plug_and_play: "即插即用",
  adjustable_tilt_legs: "可调支脚",
  video_resolution: "视频分辨率",
  video_fps: "帧率（fps）",
  microphone: "麦克风",
  light_correction: "光线校正",
  mount: "固定方式",
  driver_diameter_mm: "驱动单元（毫米）",
  noise_reducing_microphone: "降噪麦克风",
  inline_controls: "线控功能",
};
export const specValue = (value) =>
  typeof value === "boolean"
    ? value
      ? "支持"
      : "不支持"
    : Array.isArray(value)
      ? value.join("、")
      : typeof value === "object"
        ? JSON.stringify(value)
        : String(value);
