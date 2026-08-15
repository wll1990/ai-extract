import defAvatarImg from './components/def-avatar.png';

/**
 * 默认头像 URL。
 * Next.js 的类型声明把 *.png 标为 StaticImageData，但运行时 webpack 实际返回 URL 字符串；
 * 这里统一断言为 string，供 <img src> 直接使用（standalone 部署下 public 目录不被服务，
 * 只能靠 static import 打进 .next/static）。
 */
export const defAvatar = defAvatarImg as unknown as string;
